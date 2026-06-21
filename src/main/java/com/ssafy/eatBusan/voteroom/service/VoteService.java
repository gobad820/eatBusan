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
import com.ssafy.eatBusan.voteroom.service.VoteRoomCacheService.TallySnapshot;
import java.util.List;
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
    public VoteResponse cast(String publicId, Long memberId, List<Long> candidateIds) {
        // ballot 검증: 비어 있지 않음 / 최대 3개 / 후보 중복 없음.
        validateBallot(candidateIds);

        // close()와 같은 방 행 잠금을 공유한다. 잠금이 커밋까지 유지되므로
        // "isClosed 검사 통과 → 호스트가 마감 커밋 → CLOSED 방에 표 커밋" 인터리빙이 차단되고,
        // close 커밋 후 시작한 cast는 여기서 CLOSED를 보고 409로 거부된다.
        VoteRoom room = voteRoomRepository.findWithLockByPublicIdAndDeletedFalse(publicId)
            .orElseThrow(() -> new EBException(ErrorCode.VOTE_ROOM_NOT_FOUND));
        if (room.isClosed()) {
            throw new EBException(ErrorCode.VOTE_ROOM_CLOSED);
        }
        VoteParticipant me = voteParticipantRepository
            .findByRoomIdAndMemberIdAndDeletedFalse(room.getId(), memberId)
            .orElseThrow(() -> new EBException(ErrorCode.NOT_ROOM_PARTICIPANT));
        // ballot의 각 후보가 이 방의 후보인지 전수 확인한다.
        for (Long candidateId : candidateIds) {
            if (!voteCandidateRepository.existsByIdAndRoomIdAndDeletedFalse(candidateId, room.getId())) {
                throw new EBException(ErrorCode.CANDIDATE_NOT_IN_ROOM);
            }
        }

        // 투표 행위는 곧 방 입장이다 — INVITED 상태였다면 JOINED로 전환한다.
        me.join();

        CastResult result;
        try {
            // tally ZSET이 DB 기준으로 초기화되어 있는지 먼저 보장한다.
            // ensureBootstrap은 DB -> Redis 로드만 하고, DB를 변경하지 않는다.
            voteRoomCacheService.ensureBootstrap(publicId, room.getId());

            // Redis Lua script로 "이전 ballot 점수 차감 + 새 ballot 점수 가산"을 원자적으로 먼저 처리한다.
            result = voteRoomCacheService.cast(publicId, memberId, candidateIds);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable, using DB fallback. publicId={} memberId={}",
                publicId, memberId, e);
            return fallbackToDb(room, memberId, candidateIds);
        }

        // 같은 ballot 재제출(changed=false)이면 Redis도 DB도 바꿀 것이 없다 — 멱등.
        // sync/compensate/broadcast를 모두 생략하고 현재 집계만 돌려준다.
        if (!result.changed()) {
            TallySnapshot snapshot = voteRoomCacheService.getTally(publicId, room.getId());
            long votedCount = voteRepository.countDistinctVotersByRoomId(room.getId());
            return new VoteResponse(candidateIds, snapshot.entries(), votedCount);
        }

        // Redis는 이미 바뀐 상태이므로, DB 동기화 실패 시 Redis를 되돌린 뒤 예외를 다시 던진다.
        // 예외를 삼키고 성공 응답을 주면 클라이언트와 DB/Redis 상태가 서로 어긋난다.
        try {
            syncToDb(room.getId(), memberId, candidateIds);
        } catch (Exception e) {
            log.warn("DB sync failed, compensating Redis. publicId={} memberId={}",
                publicId, memberId, e);
            try {
                voteRoomCacheService.compensate(publicId, memberId,
                    result.prevBallot(), candidateIds);
            } catch (Exception compensationException) {
                e.addSuppressed(compensationException);
                log.error("Redis compensation failed. publicId={} memberId={}",
                    publicId, memberId, compensationException);
            }
            throw e;
        }

        TallySnapshot snapshot = voteRoomCacheService.getTally(publicId, room.getId());
        // votedCount는 DB count — syncToDb(insert+flush) 이후에 계산한다.
        long votedCount = voteRepository.countDistinctVotersByRoomId(room.getId());

        // 집계가 실제로 바뀐 경우(여기까지 온 경로)만 커밋 후 broadcast를 예약한다.
        voteRoomBroadcaster.broadcastTallyUpdated(publicId, snapshot, votedCount);

        return new VoteResponse(candidateIds, snapshot.entries(), votedCount);
    }

    private void validateBallot(List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            throw new EBException(ErrorCode.BALLOT_EMPTY);
        }
        if (candidateIds.size() > 3) {
            throw new EBException(ErrorCode.BALLOT_TOO_MANY);
        }
        if (candidateIds.stream().distinct().count() != candidateIds.size()) {
            throw new EBException(ErrorCode.BALLOT_DUPLICATE_CANDIDATE);
        }
    }

    private void syncToDb(Long roomId, Long memberId, List<Long> candidateIds) {
        // ballot 교체는 update가 아니라 "기존 표 물리 삭제 후 rank별 새 row insert"다.
        // (room_id,member_id,rank)·(room_id,member_id,candidate_id) unique 재사용 충돌을 피하려면
        // 새 ballot을 넣기 전에 기존 표를 먼저 비워야 한다.
        voteRepository.deleteByRoomIdAndMemberId(roomId, memberId);
        // delete를 새 insert보다 먼저 DB에 반영한다 (같은 트랜잭션 내 unique 충돌 방지).
        voteRepository.flush();

        for (int i = 0; i < candidateIds.size(); i++) {
            // rank는 1부터. candidateIds[0]=1등 → rank 1.
            voteRepository.save(Vote.of(roomId, candidateIds.get(i), memberId, i + 1));
        }

        // JPA save는 SQL 실행을 트랜잭션 commit 시점까지 미룰 수 있다.
        // 여기서 flush해야 DB 예외를 cast()의 catch에서 잡고 Redis compensate를 수행할 수 있다.
        voteRepository.flush();
    }

    // Redis 다운 시 DB만으로 투표를 처리하고 DB 기준 집계를 돌려준다.
    private VoteResponse fallbackToDb(VoteRoom room, Long memberId, List<Long> candidateIds) {
        // fallback 기간의 표는 Redis tally/ballot에 반영되지 못한다.
        // best-effort로 initKey를 무효화해 두면 부분 복구 시 즉시,
        // 아니어도 initKey TTL 만료 시 bootstrap이 DB 기준으로 재적재해 수렴한다.
        voteRoomCacheService.tryInvalidateBootstrap(room.getPublicId());

        syncToDb(room.getId(), memberId, candidateIds);
        List<TallyEntry> tally = voteRoomCacheService.tallyFromDb(room.getId());
        // votedCount는 DB count — syncToDb 이후에 계산한다.
        long votedCount = voteRepository.countDistinctVotersByRoomId(room.getId());

        // fallback 경로도 DB 상태는 바뀌었으므로 커밋 후 broadcast한다. 버전은 미상(UNVERSIONED).
        // (같은 ballot 재제출 판별이 없어 드물게 불변 push가 갈 수 있으나, 화면은 같은 집계로 갱신될 뿐이다.)
        voteRoomBroadcaster.broadcastTallyUpdated(room.getPublicId(),
            new TallySnapshot(VoteRoomCacheService.UNVERSIONED, tally), votedCount);

        return new VoteResponse(candidateIds, tally, votedCount);
    }
}
