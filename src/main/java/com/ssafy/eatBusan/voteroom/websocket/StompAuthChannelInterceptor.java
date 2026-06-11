package com.ssafy.eatBusan.voteroom.websocket;

import com.ssafy.eatBusan.auth.domain.TokenType;
import com.ssafy.eatBusan.auth.util.JWTUtil;
import com.ssafy.eatBusan.global.exception.EBException;
import com.ssafy.eatBusan.global.exception.ErrorCode;
import com.ssafy.eatBusan.voteroom.service.VoteRoomService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP 인바운드 채널 인터셉터 (설계 §7.3).
 *
 * - HTTP의 JwtFilter는 WebSocket 프레임을 타지 않는다.
 *   CONNECT에서 직접 JWT를 검증해 Principal(memberId)을 심고,
 *   SUBSCRIBE에서 "그 방 참가자인가"를 한 번 더 인가한다 (+ INVITED -> JOINED 전환, 설계 §4.2).
 * - 이 서비스의 쓰기는 전부 REST로만 받으므로(설계 §2-4) 클라이언트 SEND 프레임은 전부 거부한다.
 *   SimpleBroker는 클라이언트가 /topic으로 직접 SEND한 페이로드를 그대로 전 구독자에게 중계하기 때문에,
 *   막지 않으면 인증만 통과한 아무 회원이나 가짜 TALLY_UPDATED/ROOM_CLOSED를 위조 broadcast할 수 있다.
 * - 여기서 던진 예외는 ERROR 프레임으로 클라이언트에 전달되며 연결/구독/전송이 거부된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";

    // 브로커 네임스페이스(WebSocketConfig.enableSimpleBroker와 일치)
    private static final String BROKER_DESTINATION_PREFIX = "/topic";

    // 구독은 정확히 /topic/vote-rooms/{publicId} 형태(정규형)만 허용한다 — deny-by-default.
    // SimpleBroker는 Ant 패턴 매칭으로 구독을 처리하므로 "/topic/**", "/topic/*" 같은
    // 와일드카드 구독을 허용하면 참가자 검사 없이 전 방의 broadcast를 도청할 수 있다.
    private static final Pattern VOTE_ROOM_TOPIC_PATTERN =
        Pattern.compile("^/topic/vote-rooms/([A-Za-z0-9_]+)$");

    private final JWTUtil jwtUtil;

    // WebSocketConfig가 이 인터셉터를 컨텍스트 초기화 극초반에 끌어올리므로,
    // 서비스 빈은 ObjectProvider 지연 주입으로 순환참조/조기초기화를 피한다.
    private final ObjectProvider<VoteRoomService> voteRoomServiceProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            rejectSend(accessor);
        }
        return message;
    }

    // CONNECT: connectHeaders의 Authorization("Bearer <token>")을 검증하고 Principal을 심는다.
    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
        if (authorization == null || authorization.isBlank()) {
            throw new EBException(ErrorCode.TOKEN_NOT_FOUND);
        }

        // JWTUtil은 ACCESS 타입일 때 내부에서 substring(7)을 수행한다.
        // 따라서 "Bearer " 접두사를 포함한 원문을 그대로 넘겨야 한다 (설계 §14).
        if (!jwtUtil.validateToken(authorization, TokenType.ACCESS)) {
            throw new EBException(ErrorCode.TOKEN_INVALID);
        }

        Long memberId = jwtUtil.getId(authorization, TokenType.ACCESS);
        // CONNECT에서 심은 Principal은 같은 세션의 이후 프레임(SUBSCRIBE 등)에 자동으로 붙는다.
        accessor.setUser(new StompPrincipal(memberId));
        log.debug("STOMP CONNECT authenticated. memberId={}", memberId);
    }

    // SUBSCRIBE: /topic 네임스페이스는 정규형 /topic/vote-rooms/{publicId}만, 그 방의 참가자에게만 허용한다 (설계 §4.3).
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(BROKER_DESTINATION_PREFIX)) {
            // 브로커(/topic) 밖 destination은 broadcast 대상이 아니므로 이 인터셉터의 인가 대상이 아니다.
            return;
        }

        if (!(accessor.getUser() instanceof StompPrincipal principal)) {
            // CONNECT 인증 없이 구독 프레임이 오는 경우 — 정상 클라이언트에서는 발생하지 않는다.
            throw new EBException(ErrorCode.TOKEN_NOT_FOUND);
        }

        Matcher matcher = VOTE_ROOM_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            // 와일드카드(*, **)·비정규 destination은 전부 거부 — 패턴 구독을 통한 전 방 도청 차단.
            log.warn("STOMP SUBSCRIBE rejected (non-canonical destination). destination={} memberId={}",
                destination, principal.memberId());
            throw new EBException(ErrorCode.NOT_ROOM_PARTICIPANT);
        }

        // 참가자 검증 + INVITED -> JOINED 전환 (설계 §4.2: 방 입장/구독 = JOINED 트리거).
        // 인터셉터는 트랜잭션 밖에서 동작하므로 상태 변경은 @Transactional 서비스 메서드를 거친다.
        String publicId = matcher.group(1);
        voteRoomServiceProvider.getObject().joinOnSubscribe(publicId, principal.memberId());
        log.debug("STOMP SUBSCRIBE authorized. publicId={} memberId={}", publicId, principal.memberId());
    }

    // SEND: WebSocket은 push 전용 "알림 채널"이다(설계 §2-4). 쓰기는 전부 REST로만 받으므로
    // 클라이언트 SEND는 목적지와 무관하게 거부한다 — /topic 직접 SEND로 broadcast를 위조하는 공격 차단.
    private void rejectSend(StompHeaderAccessor accessor) {
        log.warn("STOMP SEND rejected. destination={} user={}",
            accessor.getDestination(), accessor.getUser());
        throw new EBException(ErrorCode.WS_SEND_NOT_ALLOWED);
    }
}
