package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

// STOMP push 페이로드 — 마감 (설계 §7.4)
public record RoomClosedMessage(String type, Long winnerCandidateId, List<TallyEntry> tally) {

    public static RoomClosedMessage of(Long winnerCandidateId, List<TallyEntry> tally) {
        return new RoomClosedMessage("ROOM_CLOSED", winnerCandidateId, tally);
    }
}
