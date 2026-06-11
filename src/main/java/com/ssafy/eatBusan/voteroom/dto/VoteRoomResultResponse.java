package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

// GET /result(집계 스냅샷)와 POST /close(마감 결과)가 같은 모양을 공유한다.
public record VoteRoomResultResponse(
        String status,
        Long winnerCandidateId,
        List<TallyEntry> tally
) {
}
