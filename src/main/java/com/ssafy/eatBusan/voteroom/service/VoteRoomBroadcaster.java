package com.ssafy.eatBusan.voteroom.service;

import com.ssafy.eatBusan.voteroom.dto.RoomClosedMessage;
import com.ssafy.eatBusan.voteroom.dto.TallyUpdatedMessage;
import com.ssafy.eatBusan.voteroom.service.VoteRoomCacheService.TallySnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 투표방 이벤트를 /topic/vote-rooms/{publicId} 구독자에게 push한다 (설계 §7.4~7.5).
 *
 * - 반드시 트랜잭션 "커밋 후"에만 전송한다. 커밋 전에 push하면
 *   DB 롤백 시 구독자 화면이 거짓 집계를 보게 된다.
 * - push 실패가 이미 커밋된 투표/마감을 실패시키면 안 되므로 예외는 삼키고 로그만 남긴다.
 *   (클라이언트는 GET /result 스냅샷 폴링/재구독으로 복구한다 — 설계 §8(5))
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoteRoomBroadcaster {

    private static final String TOPIC_PREFIX = "/topic/vote-rooms/";

    private final SimpMessagingTemplate messagingTemplate;

    // 투표/표 변경 성공 시 — 집계 broadcast.
    // 트랜잭션 간 커밋(=전송) 순서는 스냅샷을 읽은 순서와 다를 수 있으므로,
    // 페이로드에 방별 단조 증가 version을 실어 클라이언트가 역행(stale) 스냅샷을 버리게 한다.
    public void broadcastTallyUpdated(String publicId, TallySnapshot snapshot, long votedCount) {
        sendAfterCommit(publicId, TallyUpdatedMessage.of(snapshot.version(), snapshot.entries(), votedCount));
    }

    // 마감 시 — 승자 + 최종 집계 broadcast (멱등 경로에서는 호출하지 말 것)
    public void broadcastRoomClosed(String publicId, Long winnerCandidateId, TallySnapshot snapshot, long votedCount) {
        sendAfterCommit(publicId, RoomClosedMessage.of(winnerCandidateId, snapshot.version(), snapshot.entries(), votedCount));
    }

    private void sendAfterCommit(String publicId, Object payload) {
        // @Transactional 경로가 아니라면(이론상 없음) 방어적으로 즉시 전송한다.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(publicId, payload);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(publicId, payload);
            }
        });
    }

    private void send(String publicId, Object payload) {
        try {
            messagingTemplate.convertAndSend(TOPIC_PREFIX + publicId, payload);
        } catch (Exception e) {
            log.warn("STOMP broadcast failed. publicId={}", publicId, e);
        }
    }
}
