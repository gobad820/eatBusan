package com.ssafy.eatBusan.voteroom.repository;

import com.ssafy.eatBusan.voteroom.domain.VoteCandidate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoteCandidateRepository extends JpaRepository<VoteCandidate, Long> {

    List<VoteCandidate> findAllByRoomIdAndDeletedFalse(Long roomId);

    boolean existsByIdAndRoomIdAndDeletedFalse(Long id, Long roomId);

    // 방 단발성 hard delete: 방의 모든 후보를 물리 삭제.
    @Modifying
    @Query("DELETE FROM VoteCandidate c WHERE c.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);
}
