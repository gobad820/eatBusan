package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

// STOMP push 페이로드 — 투표 갱신 (설계 §7.4)
public record TallyUpdatedMessage(String type, List<TallyEntry> tally) {

    public static TallyUpdatedMessage of(List<TallyEntry> tally) {
        return new TallyUpdatedMessage("TALLY_UPDATED", tally);
    }
}
