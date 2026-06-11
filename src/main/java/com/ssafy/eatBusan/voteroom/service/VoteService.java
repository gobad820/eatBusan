package com.ssafy.eatBusan.voteroom.service;

import com.ssafy.eatBusan.global.exception.EBException;
import com.ssafy.eatBusan.global.exception.ErrorCode;
import com.ssafy.eatBusan.voteroom.domain.Vote;
import com.ssafy.eatBusan.voteroom.domain.VoteParticipant;
import com.ssafy.eatBusan.voteroom.domain.VoteRoom;
import com.ssafy.eatBusan.voteroom.dto.TallyEntry;
import com.ssafy.eatBusan.voteroom.dto.VoteResponse;
import com.ssafy.eatBusan.voteroom.repository.VoteCandidateRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteParticipantRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRoomRepository;
import com.ssafy.eatBusan.voteroom.service.VoteRoomCacheService.CastResult;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class VoteService {

    private final VoteRoomCacheService voteRoomCacheService;
    private final VoteRoomBroadcaster voteRoomBroadcaster;
    private final VoteRoomRepository voteRoomRepository;
    private final VoteParticipantRepository voteParticipantRepository;
    private final VoteCandidateRepository voteCandidateRepository;
    private final VoteRepository voteRepository;

    @Transactional
    public VoteResponse cast(String publicId, Long memberId, Long candidateId) {
        VoteRoom room = voteRoomRepository.findByPublicIdAndDeletedFalse(publicId)
            .orElseThrow(() -> new EBException(ErrorCode.VOTE_ROOM_NOT_FOUND));
        if (room.isClosed()) {
            throw new EBException(ErrorCode.VOTE_ROOM_CLOSED);
        }
        VoteParticipant me = voteParticipantRepository
            .findByRoomIdAndMemberIdAndDeletedFalse(room.getId(), memberId)
            .orElseThrow(() -> new EBException(ErrorCode.NOT_ROOM_PARTICIPANT));
        if (!voteCandidateRepository.existsByIdAndRoomIdAndDeletedFalse(candidateId, room.getId())) {
            throw new EBException(ErrorCode.CANDIDATE_NOT_IN_ROOM);
        }

        // 투표 행위는 곧 방 입장이다 — INVITED 상태였다면 JOINED로 전환한다.
        me.join();

        CastResult result;
        try {
            // tally ZSET이 DB 기준으로 초기화되어 있는지 먼저 보장한다.
            // ensureBootstrap은 DB -> Redis 로드만 하고, DB를 변경하지 않는다.
            voteRoomCacheService.ensureBootstrap(publicId, room.getId());

            // Redis Lua script로 "이전 표 차감 + 새 표 +1"을 원자적으로 먼저 처리한다.
            // changed=false면 같은 후보 재클릭(멱등)이라 Redis도 DB도 바꿀 게 없다.
            result = voteRoomCacheService.cast(publicId, memberId, candidateId);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, using DB fallback. publicId={} memberId={}",
                publicId, memberId, e);
            return fallbackToDb(room, memberId, candidateId);
        }

        // Redis는 이미 바뀐 상태이므로, DB 동기화 실패 시 Redis를 되돌린 뒤 예외를 다시 던진다.
        // 예외를 삼키고 성공 응답을 주면 클라이언트와 DB/Redis 상태가 서로 어긋난다.
        if (result.changed()) {
            try {
                syncToDb(room.getId(), memberId, candidateId);
            } catch (Exception e) {
                log.warn("DB sync failed, compensating Redis. publicId={} memberId={}",
                    publicId, memberId, e);
                try {
                    voteRoomCacheService.compensate(publicId, memberId,
                        result.prevCandidateId(), candidateId);
                } catch (Exception compensationException) {
                    e.addSuppressed(compensationException);
                    log.error("Redis compensation failed. publicId={} memberId={}",
                        publicId, memberId, compensationException);
                }
                throw e;
            }
        }

        List<TallyEntry> tally = voteRoomCacheService.getTally(publicId, room.getId());

        // 집계가 실제로 바뀐 경우에만 커밋 후 broadcast를 예약한다.
        // 같은 후보 재클릭(changed=false)은 push할 변화 자체가 없다 (멱등).
        if (result.changed()) {
            voteRoomBroadcaster.broadcastTallyUpdated(publicId, tally);
        }

        return new VoteResponse(candidateId, tally);
    }

    private void syncToDb(Long roomId, Long memberId, Long candidateId) {
        // deleted=true row도 찾아야 한다. 1인 1표(unique room+member)라서
        // 표 변경/재행사는 새 row insert가 아니라 기존 row update가 맞다.
        Optional<Vote> exist = voteRepository.findIncludingDeleted(roomId, memberId);

        if (exist.isPresent()) {
            Vote vote = exist.get();
            vote.changeCandidate(candidateId);
            vote.restore();
        } else {
            voteRepository.save(Vote.of(roomId, candidateId, memberId));
        }

        // JPA save/update는 SQL 실행을 트랜잭션 commit 시점까지 미룰 수 있다.
        // 여기서 flush해야 DB 예외를 cast()의 catch에서 잡고 Redis compensate를 수행할 수 있다.
        voteRepository.flush();
    }

    // Redis 다운 시 DB만으로 투표를 처리하고 DB 기준 집계를 돌려준다.
    private VoteResponse fallbackToDb(VoteRoom room, Long memberId, Long candidateId) {
        syncToDb(room.getId(), memberId, candidateId);
        List<TallyEntry> tally = voteRoomCacheService.tallyFromDb(room.getId());

        // fallback 경로도 DB 상태는 바뀌었으므로 커밋 후 broadcast한다.
        // (같은 후보 재클릭 판별이 없어 드물게 불변 push가 갈 수 있으나, 화면은 같은 집계로 갱신될 뿐이다.)
        voteRoomBroadcaster.broadcastTallyUpdated(room.getPublicId(), tally);

        return new VoteResponse(candidateId, tally);
    }
}
