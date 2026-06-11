package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

public record VoteRoomDetailResponse(
        String roomPublicId,
        String title,
        Long hostMemberId,
        String status,
        Long winnerCandidateId,
        Long myCandidateId,
        List<CandidateResponse> candidates,
        List<ParticipantResponse> participants
) {
}
