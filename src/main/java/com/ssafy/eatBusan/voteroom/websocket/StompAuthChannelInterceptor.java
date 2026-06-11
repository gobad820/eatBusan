package com.ssafy.eatBusan.voteroom.websocket;

import com.ssafy.eatBusan.auth.domain.TokenType;
import com.ssafy.eatBusan.auth.util.JWTUtil;
import com.ssafy.eatBusan.global.exception.EBException;
import com.ssafy.eatBusan.global.exception.ErrorCode;
import com.ssafy.eatBusan.voteroom.domain.VoteRoom;
import com.ssafy.eatBusan.voteroom.repository.VoteParticipantRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRoomRepository;
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
 *   SUBSCRIBE에서 "그 방 참가자인가"를 한 번 더 인가한다.
 * - 여기서 던진 예외는 ERROR 프레임으로 클라이언트에 전달되며 연결/구독이 거부된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String VOTE_ROOM_TOPIC_PREFIX = "/topic/vote-rooms/";

    private final JWTUtil jwtUtil;

    // WebSocketConfig가 이 인터셉터를 컨텍스트 초기화 극초반에 끌어올리므로,
    // JPA 레포지토리는 ObjectProvider 지연 주입으로 순환참조/조기초기화를 피한다.
    private final ObjectProvider<VoteRoomRepository> voteRoomRepositoryProvider;
    private final ObjectProvider<VoteParticipantRepository> voteParticipantRepositoryProvider;

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

    // SUBSCRIBE: /topic/vote-rooms/{publicId}는 그 방의 VoteParticipant만 구독할 수 있다 (설계 §4.3).
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(VOTE_ROOM_TOPIC_PREFIX)) {
            // 투표방 외 destination은 이 인터셉터의 인가 대상이 아니다.
            return;
        }

        if (!(accessor.getUser() instanceof StompPrincipal principal)) {
            // CONNECT 인증 없이 구독 프레임이 오는 경우 — 정상 클라이언트에서는 발생하지 않는다.
            throw new EBException(ErrorCode.TOKEN_NOT_FOUND);
        }

        String publicId = destination.substring(VOTE_ROOM_TOPIC_PREFIX.length());
        VoteRoom room = voteRoomRepositoryProvider.getObject()
            .findByPublicIdAndDeletedFalse(publicId)
            .orElseThrow(() -> new EBException(ErrorCode.VOTE_ROOM_NOT_FOUND));

        if (!voteParticipantRepositoryProvider.getObject()
            .existsByRoomIdAndMemberIdAndDeletedFalse(room.getId(), principal.memberId())) {
            log.warn("STOMP SUBSCRIBE rejected. publicId={} memberId={}", publicId, principal.memberId());
            throw new EBException(ErrorCode.NOT_ROOM_PARTICIPANT);
        }
        log.debug("STOMP SUBSCRIBE authorized. publicId={} memberId={}", publicId, principal.memberId());
    }
}
