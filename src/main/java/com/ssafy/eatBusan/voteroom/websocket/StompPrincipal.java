package com.ssafy.eatBusan.voteroom.websocket;

import java.security.Principal;

// CONNECT 프레임 인증 성공 시 STOMP 세션에 심는 인증 주체.
// getName()은 세션 식별자로 쓰이므로 memberId 문자열을 그대로 반환한다.
public record StompPrincipal(Long memberId) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(memberId);
    }
}
