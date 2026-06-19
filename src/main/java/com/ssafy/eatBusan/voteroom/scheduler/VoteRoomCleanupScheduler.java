package com.ssafy.eatBusan.voteroom.scheduler;

import com.ssafy.eatBusan.voteroom.domain.VoteRoom;
import com.ssafy.eatBusan.voteroom.service.VoteRoomCleanupService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 단발성: 마감 후 N분 경과한 방을 1분 주기로 물리 삭제한다. 삭제 로직은 서비스에 위임하고 여기선 호출만.
@Component
@RequiredArgsConstructor
@Slf4j
public class VoteRoomCleanupScheduler {

    // 마감 후 삭제까지의 유예(분). property voteroom.delete-delay-minutes, 기본 10.
    @Value("${voteroom.delete-delay-minutes:10}")
    private long deleteDelayMinutes;

    private final VoteRoomCleanupService voteRoomCleanupService;

    // 이전 실행 종료 후 1분 뒤 다시 실행 (작업 겹침 방지).
    @Scheduled(fixedDelayString = "60000")
    public void cleanupExpiredRooms() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(deleteDelayMinutes);
        List<VoteRoom> expired = voteRoomCleanupService.findExpiredRooms(threshold);
        if (expired.isEmpty()) {
            return;
        }

        int deleted = 0;
        for (VoteRoom room : expired) {
            try {
                voteRoomCleanupService.cleanup(room);
                deleted++;
            } catch (Exception e) {
                // 한 방의 삭제 실패가 나머지 방 정리를 막지 않게 격리한다. 다음 주기에 재시도된다.
                log.warn("Vote room cleanup failed. publicId={}", room.getPublicId(), e);
            }
        }
        log.info("Vote room cleanup done. deleted={}/{}", deleted, expired.size());
    }
}
