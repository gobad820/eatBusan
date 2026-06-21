package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

// GET /result(집계 스냅샷)와 POST /close(마감 결과)가 같은 모양을 공유한다.
// version: 방별 단조 증가 버전 — 클라이언트가 STOMP TALLY_UPDATED의 역행 스냅샷을 버릴 때
//          기준값(lastVersion)으로 쓴다. 0은 "버전 미상(DB fallback)".
public record VoteRoomResultResponse(
        String status,
        Long winnerCandidateId,
        long version,
        List<TallyEntry> tally,
        long votedCount
) {
}
