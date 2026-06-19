package com.ssafy.eatBusan.voteroom.domain;

import com.ssafy.eatBusan.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "vote",
        uniqueConstraints = {
                // 한 투표자는 방 안에서 각 순위(1~3)를 한 번씩만 사용한다.
                @UniqueConstraint(columnNames = {"room_id", "member_id", "ballot_rank"}),
                // 한 투표자는 방 안에서 같은 후보를 두 순위에 중복으로 넣을 수 없다.
                @UniqueConstraint(columnNames = {"room_id", "member_id", "candidate_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vote extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // 순위: 1(1등)~3(3등). 점수는 pointsOf(rank)로 환산한다.
    // 컬럼명은 ballot_rank — "rank"는 MySQL 8 예약어(RANK() 윈도우 함수)라 DDL이 깨진다.
    @Column(name = "ballot_rank", nullable = false)
    private int rank;

    public static Vote of(Long roomId, Long candidateId, Long memberId, int rank) {
        Vote vote = new Vote();
        vote.roomId = roomId;
        vote.candidateId = candidateId;
        vote.memberId = memberId;
        vote.rank = rank;
        return vote;
    }

    // 순위→점수 매핑. rank1=5, rank2=3, rank3=1, 그 외 0.
    // !! 동기화 필요 !! 이 매핑은 vote-cast.lua 와 동일하게 유지해야 한다.
    //                  한쪽만 바꾸면 Redis 집계와 DB fallback 집계가 어긋난다.
    public static int pointsOf(int rank) {
        return rank == 1 ? 5 : rank == 2 ? 3 : rank == 3 ? 1 : 0;
    }
}
