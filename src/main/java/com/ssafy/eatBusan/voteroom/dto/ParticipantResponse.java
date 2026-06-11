package com.ssafy.eatBusan.voteroom.dto;

import com.ssafy.eatBusan.voteroom.domain.VoteParticipant;

public record ParticipantResponse(
        Long memberId,
        String status
) {

    public static ParticipantResponse from(VoteParticipant participant) {
        return new ParticipantResponse(participant.getMemberId(), participant.getStatus().name());
    }
}
