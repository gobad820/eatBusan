package com.ssafy.eatBusan.voteroom.dto;

import com.ssafy.eatBusan.voteroom.domain.VoteCandidate;

public record CandidateResponse(
        Long candidateId,
        Long placeId,
        String placeName
) {

    public static CandidateResponse from(VoteCandidate candidate) {
        return new CandidateResponse(candidate.getId(), candidate.getPlaceId(), candidate.getPlaceName());
    }
}
