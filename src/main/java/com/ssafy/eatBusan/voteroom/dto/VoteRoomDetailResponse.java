package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

public record VoteRoomDetailResponse(
        String roomPublicId,
        String title,
        Long hostMemberId,
        String status,
        Long winnerCandidateId,
        String inviteCode,
        boolean amHost,
        List<Long> myBallot,
        List<CandidateResponse> candidates,
        List<ParticipantResponse> participants
) {
}
