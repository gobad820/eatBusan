package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

// 순위 ballot: candidateIds[0]=1등, [1]=2등, [2]=3등. 1~3개, 중복 없음.
public record VoteRequest(
        List<Long> candidateIds
) {
}
