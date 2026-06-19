package com.ssafy.eatBusan.voteroom.repository;

import com.ssafy.eatBusan.voteroom.domain.VoteParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoteParticipantRepository extends JpaRepository<VoteParticipant, Long> {

    Optional<VoteParticipant> findByRoomIdAndMemberIdAndDeletedFalse(Long roomId, Long memberId);

    boolean existsByRoomIdAndMemberIdAndDeletedFalse(Long roomId, Long memberId);

    List<VoteParticipant> findAllByRoomIdAndDeletedFalse(Long roomId);

    // 방 단발성 hard delete: 방의 모든 참가자를 물리 삭제.
    @Modifying
    @Query("DELETE FROM VoteParticipant p WHERE p.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);
}
