package com.ssafy.eatBusan.voteroom.domain;

import com.ssafy.eatBusan.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
        name = "vote_participant",
        uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "member_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoteParticipant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private VoteParticipantStatus status;

    public static VoteParticipant invited(Long roomId, Long memberId) {
        return of(roomId, memberId, VoteParticipantStatus.INVITED);
    }

    // 호스트는 초대 절차 없이 처음부터 JOINED로 등록해 바로 투표할 수 있게 한다.
    public static VoteParticipant joined(Long roomId, Long memberId) {
        return of(roomId, memberId, VoteParticipantStatus.JOINED);
    }

    private static VoteParticipant of(Long roomId, Long memberId, VoteParticipantStatus status) {
        VoteParticipant participant = new VoteParticipant();
        participant.roomId = roomId;
        participant.memberId = memberId;
        participant.status = status;
        return participant;
    }

    public void join() {
        this.status = VoteParticipantStatus.JOINED;
    }
}
