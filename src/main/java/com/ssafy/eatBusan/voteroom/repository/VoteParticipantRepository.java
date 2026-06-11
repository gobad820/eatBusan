package com.ssafy.eatBusan.voteroom.repository;

import com.ssafy.eatBusan.voteroom.domain.VoteParticipant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoteParticipantRepository extends JpaRepository<VoteParticipant, Long> {

    Optional<VoteParticipant> findByRoomIdAndMemberIdAndDeletedFalse(Long roomId, Long memberId);

    boolean existsByRoomIdAndMemberIdAndDeletedFalse(Long roomId, Long memberId);

    List<VoteParticipant> findAllByRoomIdAndDeletedFalse(Long roomId);
}
