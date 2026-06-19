package com.ssafy.eatBusan.voteroom.service;

import com.ssafy.eatBusan.voteroom.domain.VoteRoom;
import com.ssafy.eatBusan.voteroom.domain.VoteRoomStatus;
import com.ssafy.eatBusan.voteroom.repository.VoteCandidateRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteParticipantRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 단발성 요구사항: 마감 후 N분 경과 시 방을 물리 삭제한다 (조회 시 404로 사라짐).
@Service
@RequiredArgsConstructor
@Slf4j
public class VoteRoomCleanupService {

    private final VoteRoomRepository voteRoomRepository;
    private final VoteRepository voteRepository;
    private final VoteCandidateRepository voteCandidateRepository;
    private final VoteParticipantRepository voteParticipantRepository;
    private final VoteRoomCacheService voteRoomCacheService;

    // 마감 기준 시각보다 오래된 CLOSED 방 목록을 반환한다 (DB 트랜잭션 밖 조회).
    @Transactional(readOnly = true)
    public List<VoteRoom> findExpiredRooms(LocalDateTime threshold) {
        return voteRoomRepository.findAllByStatusAndClosedAtBefore(VoteRoomStatus.CLOSED, threshold);
    }

    // 한 방을 물리 삭제한다. 외래 참조 순서대로 votes -> candidates -> participants -> room.
    // Redis purge 실패가 DB 삭제를 막지 않도록, DB 삭제를 먼저 커밋한 뒤 purge를 호출한다.
    @Transactional
    public void deleteRoomData(Long roomId) {
        voteRepository.deleteByRoomId(roomId);
        voteCandidateRepository.deleteByRoomId(roomId);
        voteParticipantRepository.deleteByRoomId(roomId);
        voteRoomRepository.deleteById(roomId);
    }

    // 단발성 hard delete 진입점. cleanup 자체가 트랜잭션 경계이므로 내부 deleteRoomData 자기호출이
    // 프록시를 우회해도 같은 트랜잭션 안에서 동작한다(@Modifying 쿼리에 트랜잭션 보장).
    // Redis purge 실패가 DB 삭제를 막지 않도록, purge는 DB 커밋 이후(afterCommit)에만 수행한다.
    @Transactional
    public void cleanup(VoteRoom room) {
        deleteRoomData(room.getId());

        String publicId = room.getPublicId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    voteRoomCacheService.purge(publicId);
                } catch (Exception e) {
                    log.warn("Redis purge failed after hard delete. publicId={}", publicId, e);
                }
            }
        });
    }
}
