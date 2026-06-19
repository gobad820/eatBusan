package com.ssafy.eatBusan.voteroom.dto;

public record VoteRoomCreateRequest(
        String title,
        Double lat,
        Double lng,
        Integer radius
) {
}
