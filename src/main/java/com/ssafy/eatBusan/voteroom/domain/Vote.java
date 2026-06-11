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
        uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "member_id"})
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

    public static Vote of(Long roomId, Long candidateId, Long memberId) {
        Vote vote = new Vote();
        vote.roomId = roomId;
        vote.candidateId = candidateId;
        vote.memberId = memberId;
        return vote;
    }

    // 1인 1표(unique room+member) — 표 변경은 insert가 아니라 기존 row의 candidate update다.
    public void changeCandidate(Long candidateId) {
        this.candidateId = candidateId;
    }
}
