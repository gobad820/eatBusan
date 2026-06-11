package com.ssafy.eatBusan.voteroom.domain;

import com.ssafy.eatBusan.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "vote_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoteCandidate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    // 방 생성 시점의 가게 이름 스냅샷 — 이후 Place가 바뀌어도 투표 화면은 불변
    @Column(name = "place_name", nullable = false)
    private String placeName;

    @Column(name = "added_by", nullable = false)
    private Long addedBy;

    public static VoteCandidate of(Long roomId, Long placeId, String placeName, Long addedBy) {
        VoteCandidate candidate = new VoteCandidate();
        candidate.roomId = roomId;
        candidate.placeId = placeId;
        candidate.placeName = placeName;
        candidate.addedBy = addedBy;
        return candidate;
    }
}
