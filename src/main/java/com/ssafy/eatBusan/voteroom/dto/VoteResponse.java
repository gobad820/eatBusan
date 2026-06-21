package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

// myBallot: 내가 제출한 ballot(후보 candidateId, rank 순서).
public record VoteResponse(
        List<Long> myBallot,
        List<TallyEntry> tally,
        long votedCount
) {
}
