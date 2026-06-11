package com.ssafy.eatBusan.voteroom.repository;

import com.ssafy.eatBusan.voteroom.domain.VoteRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoteRoomRepository extends JpaRepository<VoteRoom, Long> {

    Optional<VoteRoom> findByPublicIdAndDeletedFalse(String publicId);
}
