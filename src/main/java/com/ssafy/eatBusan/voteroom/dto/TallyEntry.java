package com.ssafy.eatBusan.voteroom.dto;

// 후보별 집계 한 줄. score = 순위 ballot 점수의 합(rank1=5, rank2=3, rank3=1).
public record TallyEntry(
        Long candidateId,
        Long score
) {
}
