package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

public record VoteRoomCreateResponse(
        String roomPublicId,
        List<CandidateResponse> candidates,
        List<ParticipantResponse> participants
) {
}
