package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

// STOMP push 페이로드 — 마감 (설계 §7.4)
// version 의미는 TallyUpdatedMessage와 동일 (역행 스냅샷 판별용).
public record RoomClosedMessage(String type, Long winnerCandidateId, long version, List<TallyEntry> tally, long votedCount) {

    public static RoomClosedMessage of(Long winnerCandidateId, long version, List<TallyEntry> tally, long votedCount) {
        return new RoomClosedMessage("ROOM_CLOSED", winnerCandidateId, version, tally, votedCount);
    }
}
