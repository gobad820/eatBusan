package com.ssafy.eatBusan.voteroom.repository;

import com.ssafy.eatBusan.voteroom.domain.VoteRoom;
import com.ssafy.eatBusan.voteroom.domain.VoteRoomStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface VoteRoomRepository extends JpaRepository<VoteRoom, Long> {

    Optional<VoteRoom> findByPublicIdAndDeletedFalse(String publicId);

    // 코드 입장: 미삭제 방을 초대 코드로 찾는다.
    Optional<VoteRoom> findByInviteCodeAndDeletedFalse(String inviteCode);

    // 단발성 hard delete 스케줄러용: 마감된 방 중 closedAt이 기준 시각보다 이전인 방.
    List<VoteRoom> findAllByStatusAndClosedAtBefore(VoteRoomStatus status, LocalDateTime threshold);

    // cast()와 close()가 공유하는 방 행 잠금(SELECT ... FOR UPDATE).
    // - 진행 중인 cast가 커밋되기 전에 close가 tally를 스냅샷하지 못하게 직렬화한다
    //   ("isClosed 통과 후 마감 → CLOSED 방에 표 커밋" 경합 차단).
    // - 동시 close 이중 호출도 직렬화되어 늦은 쪽이 멱등 분기(재계산·재push 없음)를 탄다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<VoteRoom> findWithLockByPublicIdAndDeletedFalse(String publicId);
}
