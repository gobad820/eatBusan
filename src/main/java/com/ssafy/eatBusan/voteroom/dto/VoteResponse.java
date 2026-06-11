package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

public record VoteResponse(
        Long myCandidateId,
        List<TallyEntry> tally
) {
}
