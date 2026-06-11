package com.ssafy.eatBusan.voteroom.repository;

import com.ssafy.eatBusan.voteroom.domain.Vote;
import com.ssafy.eatBusan.voteroom.dto.TallyEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoteRepository extends JpaRepository<Vote, Long> {

    Optional<Vote> findByRoomIdAndMemberIdAndDeletedFalse(Long roomId, Long memberId);

    // deleted=true row도 찾는다. 표를 다시 행사하면 새 row insert가 아니라 기존 row 재사용이 맞다.
    @Query("SELECT v FROM Vote v WHERE v.roomId = :roomId AND v.memberId = :memberId")
    Optional<Vote> findIncludingDeleted(@Param("roomId") Long roomId, @Param("memberId") Long memberId);

    List<Vote> findAllByRoomIdAndDeletedFalse(Long roomId);

    // Redis 다운 시 DB fallback 집계용. 표가 없는 후보는 결과에 없으므로 호출부에서 0으로 채운다.
    @Query("SELECT new com.ssafy.eatBusan.voteroom.dto.TallyEntry(v.candidateId, COUNT(v)) "
            + "FROM Vote v WHERE v.roomId = :roomId AND v.deleted = false GROUP BY v.candidateId")
    List<TallyEntry> countTallyByRoomId(@Param("roomId") Long roomId);
}
