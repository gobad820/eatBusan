package com.ssafy.eatBusan.voteroom.repository;

import com.ssafy.eatBusan.voteroom.domain.Vote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoteRepository extends JpaRepository<Vote, Long> {

    // 한 멤버의 방 내 미삭제 표(ballot) 전체. myBallot 조회용 — 순위 오름차순.
    List<Vote> findAllByRoomIdAndMemberIdAndDeletedFalseOrderByRankAsc(Long roomId, Long memberId);

    // 방의 미삭제 표 전체. service/cache에서 후보별 점수 합산(pointsOf)에 사용.
    List<Vote> findAllByRoomIdAndDeletedFalse(Long roomId);

    // 투표 완료(ballot 제출) distinct 멤버 수. 한 멤버가 1~3행이어도 1명, 재투표해도 불변.
    @Query("SELECT COUNT(DISTINCT v.memberId) FROM Vote v WHERE v.roomId = :roomId AND v.deleted = false")
    long countDistinctVotersByRoomId(@Param("roomId") Long roomId);

    // ballot 교체: 기존 표를 행 삭제(soft가 아닌 물리 삭제) 후 새 ballot을 insert한다.
    // 한 멤버의 방 내 표를 일괄 물리 삭제한다. (room_id,member_id,rank) unique 재사용 충돌 회피.
    @Modifying
    @Query("DELETE FROM Vote v WHERE v.roomId = :roomId AND v.memberId = :memberId")
    void deleteByRoomIdAndMemberId(@Param("roomId") Long roomId, @Param("memberId") Long memberId);

    // 방 단발성 hard delete: 방의 모든 표를 물리 삭제.
    @Modifying
    @Query("DELETE FROM Vote v WHERE v.roomId = :roomId")
    void deleteByRoomId(@Param("roomId") Long roomId);
}
