package com.ssafy.eatBusan.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Member
    MEMBER_DUPLICATE(HttpStatus.CONFLICT, "이미 등록된 회원입니다"), MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND,
        "없는 회원입니다."),

    // auth
    AUTH_INVALID_LOGIN(HttpStatus.UNAUTHORIZED, "ID/PW가 틀렸습니다."),

    // jwt
    TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "토큰이 없습니다."), RTOKEN_COOKIE_NOT_FOUND(
        HttpStatus.UNAUTHORIZED, "Refresh 토큰이 없습니다."), TOKEN_INVALID(HttpStatus.UNAUTHORIZED,
        "유효하지 않은 토큰입니다."), RTOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh 토큰입니다."),

    // refreshToken
    RTOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "Refresh 토큰을 찾을 수 없습니다."), RTOKEN_MISMATCH(
        HttpStatus.UNAUTHORIZED, "Refresh 토큰이 일치하지 않습니다."),

    // post
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "없는 후기입니다"),

    // place
    PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "없는 식당입니다."),
    PLACE_AREA_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 지역에서 식당을 찾을 수 없습니다."),

    // plcaeLike
    PLACE_LIKE_DUPLICATE(HttpStatus.BAD_REQUEST, "이미 좋아요한 장소입니다."),

    // redis
    REDIS_BOOTSTRAP_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "좋아요 캐시 초기화 중입니다. 잠시 후 다시 시도해주세요."),

    // comment
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "없는 댓글입니다."), COMMENT_CONTENT_EMPTY(
        HttpStatus.BAD_REQUEST, "댓글의 본문은 공백이 될 수 없습니다."), INVALID_PAGE_SIZE(HttpStatus.BAD_REQUEST,
        "페이지 사이즈는 0보다 커야 합니다."),

    // S3
    IMAGE_UPLOAD_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR,
        "S3 이미지 업로드에 실패했습니다."),
    NOT_IMAGE_FILE(HttpStatus.BAD_REQUEST, "이미지 파일만 업로드할 수 있습니다."), EMPTY_IMAGE_FILE(HttpStatus.BAD_REQUEST,
        "빈 이미지 파일입니다."),

    // voteRoom
    VOTE_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "없는 투표방입니다."),
    VOTE_ROOM_CLOSED(HttpStatus.CONFLICT, "이미 마감된 투표방입니다."),
    NOT_ROOM_PARTICIPANT(HttpStatus.FORBIDDEN, "투표방 참가자가 아닙니다."),
    NOT_ROOM_HOST(HttpStatus.FORBIDDEN, "투표방 호스트만 수행할 수 있습니다."),
    CANDIDATE_NOT_IN_ROOM(HttpStatus.BAD_REQUEST, "이 투표방의 후보가 아닙니다.");

    private final HttpStatus status;
    private final String message;

}
