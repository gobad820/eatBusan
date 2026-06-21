package com.ssafy.eatBusan.voteroom.dto;

import java.util.List;

// STOMP push 페이로드 — 투표 갱신 (설계 §7.4)
// version: 방별 단조 증가 버전. afterCommit 전송 순서가 스냅샷을 읽은 순서와 어긋날 수 있으므로,
//          클라이언트는 마지막으로 적용한 version 이하의 메시지를 버려 화면이 stale 집계로 역행하지 않게 한다.
//          version=0은 "버전 미상(DB fallback)" — 항상 적용한다.
public record TallyUpdatedMessage(String type, long version, List<TallyEntry> tally, long votedCount) {

    public static TallyUpdatedMessage of(long version, List<TallyEntry> tally, long votedCount) {
        return new TallyUpdatedMessage("TALLY_UPDATED", version, tally, votedCount);
    }
}
