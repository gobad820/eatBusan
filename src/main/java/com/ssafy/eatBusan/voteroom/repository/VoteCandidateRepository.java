package com.ssafy.eatBusan.voteroom.repository;

import com.ssafy.eatBusan.voteroom.domain.VoteCandidate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoteCandidateRepository extends JpaRepository<VoteCandidate, Long> {

    List<VoteCandidate> findAllByRoomIdAndDeletedFalse(Long roomId);

    boolean existsByIdAndRoomIdAndDeletedFalse(Long id, Long roomId);
}
