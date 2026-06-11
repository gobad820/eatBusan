package com.ssafy.eatBusan.voteroom.dto;

public record TallyEntry(
        Long candidateId,
        Long count
) {
}
