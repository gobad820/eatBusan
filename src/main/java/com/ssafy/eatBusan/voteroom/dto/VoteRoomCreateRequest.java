package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

public record VoteRoomCreateRequest(
        String title,
        Double lat,
        Double lng,
        Integer radius,
        List<Long> invitedMemberIds
) {
}
