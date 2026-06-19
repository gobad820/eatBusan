package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

public record VoteRoomCreateResponse(
        String roomPublicId,
        String inviteCode,
        List<CandidateResponse> candidates,
        List<ParticipantResponse> participants
) {
}
