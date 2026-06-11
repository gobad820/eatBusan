package com.ssafy.eatBusan.global.config;

import com.ssafy.eatBusan.voteroom.websocket.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// 투표방 실시간 broadcast용 WebSocket/STOMP 구성 (설계 §7.2).
// 쓰기는 전부 REST로 받고, WebSocket은 push 전용 "알림 채널"로만 쓴다.
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${spring.front.domain}")
    private String frontDomain;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 프론트 STOMP 클라이언트 접속 엔드포인트.
        // 프론트 도메인 외에 로컬 개발 포트 변동을 고려해 localhost 패턴도 허용한다.
        registry.addEndpoint("/ws-stomp")
            .setAllowedOriginPatterns(frontDomain, "http://localhost:*", "http://127.0.0.1:*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 단일 인스턴스 인메모리 브로커 — 구독 destination은 /topic/vote-rooms/{publicId}
        registry.enableSimpleBroker("/topic");
        // 투표 쓰기는 REST로만 받으므로 /app 프리픽스는 사실상 미사용 (관례상 선언)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // CONNECT의 JWT 인증 + SUBSCRIBE의 방별 참가자 인가 (설계 §7.3)
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
