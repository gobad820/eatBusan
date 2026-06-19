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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "vote_room",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"public_id"}),
                @UniqueConstraint(columnNames = {"invite_code"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoteRoom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // PK는 API에 노출하지 않는다. 경로/응답에는 publicId만 사용한다.
    @Column(name = "public_id", nullable = false, length = 20)
    private String publicId;

    @Column(nullable = false)
    private String title;

    @Column(name = "host_member_id", nullable = false)
    private Long hostMemberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private VoteRoomStatus status;

    // 마감 시 확정되는 승자 후보. OPEN 동안은 null이다.
    @Column(name = "winner_candidate_id")
    private Long winnerCandidateId;

    // 코드 입장용 초대 코드. 6자리 대문자+숫자(혼동문자 제외), 전역 unique.
    @Column(name = "invite_code", nullable = false, length = 6)
    private String inviteCode;

    // 마감 시각. OPEN 동안은 null이다. 단발성 hard delete 스케줄러의 기준 시각.
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    // 방 생성 시점의 후보 시드 조건(호스트 위치 기반) 스냅샷
    @Column(name = "seed_lat", nullable = false)
    private Double seedLat;

    @Column(name = "seed_lng", nullable = false)
    private Double seedLng;

    @Column(name = "seed_radius", nullable = false)
    private Integer seedRadius;

    public static VoteRoom of(String publicId, String title, Long hostMemberId,
            String inviteCode, Double seedLat, Double seedLng, Integer seedRadius) {
        VoteRoom voteRoom = new VoteRoom();
        voteRoom.publicId = publicId;
        voteRoom.title = title;
        voteRoom.hostMemberId = hostMemberId;
        voteRoom.inviteCode = inviteCode;
        voteRoom.status = VoteRoomStatus.OPEN;
        voteRoom.seedLat = seedLat;
        voteRoom.seedLng = seedLng;
        voteRoom.seedRadius = seedRadius;
        return voteRoom;
    }

    public boolean isHost(Long memberId) {
        return hostMemberId.equals(memberId);
    }

    public boolean isClosed() {
        return status == VoteRoomStatus.CLOSED;
    }

    public void close(Long winnerCandidateId) {
        this.status = VoteRoomStatus.CLOSED;
        this.winnerCandidateId = winnerCandidateId;
        this.closedAt = LocalDateTime.now();
    }
}
