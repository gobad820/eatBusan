package com.ssafy.eatBusan.voteroom.service;

import com.ssafy.eatBusan.global.exception.EBException;
import com.ssafy.eatBusan.global.exception.ErrorCode;
import com.ssafy.eatBusan.place.Service.PlaceService;
import com.ssafy.eatBusan.place.dto.PlaceRequestDto;
import com.ssafy.eatBusan.place.dto.PlaceResponseListDto;
import com.ssafy.eatBusan.voteroom.domain.VoteCandidate;
import com.ssafy.eatBusan.voteroom.domain.VoteParticipant;
import com.ssafy.eatBusan.voteroom.domain.VoteRoom;
import com.ssafy.eatBusan.voteroom.dto.CandidateResponse;
import com.ssafy.eatBusan.voteroom.dto.ParticipantResponse;
import com.ssafy.eatBusan.voteroom.dto.TallyEntry;
import com.ssafy.eatBusan.voteroom.dto.VoteRoomCreateRequest;
import com.ssafy.eatBusan.voteroom.dto.VoteRoomCreateResponse;
import com.ssafy.eatBusan.voteroom.dto.VoteRoomDetailResponse;
import com.ssafy.eatBusan.voteroom.dto.VoteRoomResultResponse;
import com.ssafy.eatBusan.voteroom.repository.VoteCandidateRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteParticipantRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRoomRepository;
import com.ssafy.eatBusan.voteroom.service.VoteRoomCacheService.TallySnapshot;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class VoteRoomService {

    // D1: searchPlace 결과 앞 5개를 후보로 시드한다 (거리순 무보장 — 기존 메서드 재사용 우선).
    private static final int CANDIDATE_SEED_SIZE = 5;

    // 초대 코드: 6자리, 대문자+숫자에서 혼동문자(0,O,1,I,L) 제외한 알파벳 풀.
    private static final int INVITE_CODE_LENGTH = 6;
    private static final String INVITE_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom INVITE_CODE_RANDOM = new SecureRandom();

    private final VoteRoomRepository voteRoomRepository;
    private final VoteParticipantRepository voteParticipantRepository;
    private final VoteCandidateRepository voteCandidateRepository;
    private final VoteRoomCacheService voteRoomCacheService;
    private final VoteRoomBroadcaster voteRoomBroadcaster;
    private final PlaceService placeService;

    @Transactional
    public VoteRoomCreateResponse create(Long hostMemberId, VoteRoomCreateRequest request) {
        // PlaceRequestDto는 x=경도(lng), y=위도(lat) — API의 {lat, lng}와 순서를 뒤집어 매핑한다.
        List<PlaceResponseListDto> places = placeService.searchPlace(
            new PlaceRequestDto(request.lng(), request.lat(), request.radius()));
        if (places.isEmpty()) {
            throw new EBException(ErrorCode.PLACE_AREA_NOT_FOUND);
        }

        VoteRoom room = voteRoomRepository.save(VoteRoom.of(
            generatePublicId(), request.title(), hostMemberId, generateInviteCode(),
            request.lat(), request.lng(), request.radius()));

        // 후보 시드: 검색 결과 앞 N개의 placeId/placeName 스냅샷
        List<VoteCandidate> candidates = voteCandidateRepository.saveAll(
            places.stream()
                .limit(CANDIDATE_SEED_SIZE)
                .map(place -> VoteCandidate.of(room.getId(), place.id(), place.name(), hostMemberId))
                .toList());

        // 초대 코드 입장 방식이므로, 생성 시점엔 호스트만 참가자(JOINED)로 등록한다.
        List<VoteParticipant> participants = new ArrayList<>();
        participants.add(VoteParticipant.joined(room.getId(), hostMemberId));
        voteParticipantRepository.saveAll(participants);

        // Redis tally를 모든 후보 0점으로 즉시 시드한다.
        // 실패해도 ensureBootstrap이 나중에 DB 기준으로 복구하므로 방 생성 자체는 성공시킨다.
        try {
            voteRoomCacheService.seed(room.getPublicId(),
                candidates.stream().map(VoteCandidate::getId).toList());
        } catch (Exception e) {
            log.warn("Redis seed failed, will rely on bootstrap. publicId={}", room.getPublicId(), e);
        }

        return new VoteRoomCreateResponse(
            room.getPublicId(),
            room.getInviteCode(),
            candidates.stream().map(CandidateResponse::from).toList(),
            participants.stream().map(ParticipantResponse::from).toList());
    }

    // 코드 입장: OPEN 방을 초대 코드로 찾아 호출자를 JOINED 참가자로 등록한다.
    // 이미 참가자면 멱등(상태 그대로) — 어느 쪽이든 상세 응답을 반환한다.
    @Transactional
    public VoteRoomDetailResponse join(String code, Long memberId) {
        VoteRoom room = voteRoomRepository.findByInviteCodeAndDeletedFalse(code)
            .orElseThrow(() -> new EBException(ErrorCode.INVALID_INVITE_CODE));
        if (room.isClosed()) {
            throw new EBException(ErrorCode.VOTE_ROOM_CLOSED);
        }

        boolean alreadyParticipant = voteParticipantRepository
            .existsByRoomIdAndMemberIdAndDeletedFalse(room.getId(), memberId);
        if (!alreadyParticipant) {
            voteParticipantRepository.save(VoteParticipant.joined(room.getId(), memberId));
        }

        return getDetail(room.getPublicId(), memberId);
    }

    @Transactional
    public VoteRoomDetailResponse getDetail(String publicId, Long memberId) {
        VoteRoom room = findRoom(publicId);
        VoteParticipant me = voteParticipantRepository
            .findByRoomIdAndMemberIdAndDeletedFalse(room.getId(), memberId)
            .orElseThrow(() -> new EBException(ErrorCode.NOT_ROOM_PARTICIPANT));

        // 방 상세 조회 = 입장으로 간주한다 (STOMP 구독 시점의 joinOnSubscribe와 함께 JOINED 전환 지점).
        me.join();

        List<VoteCandidate> candidates = voteCandidateRepository.findAllByRoomIdAndDeletedFalse(room.getId());
        List<VoteParticipant> participants = voteParticipantRepository.findAllByRoomIdAndDeletedFalse(room.getId());
        List<Long> myBallot = voteRoomCacheService.getMyBallot(publicId, room.getId(), memberId);

        return new VoteRoomDetailResponse(
            room.getPublicId(),
            room.getTitle(),
            room.getHostMemberId(),
            room.getStatus().name(),
            room.getWinnerCandidateId(),
            room.getInviteCode(),
            room.isHost(memberId),
            myBallot,
            candidates.stream().map(CandidateResponse::from).toList(),
            participants.stream().map(ParticipantResponse::from).toList());
    }

    // 현재 집계 스냅샷 — 폴링/재연결 직후 화면 초기화용
    public VoteRoomResultResponse getResult(String publicId, Long memberId) {
        VoteRoom room = findRoom(publicId);
        validateParticipant(room.getId(), memberId);

        TallySnapshot snapshot = voteRoomCacheService.getTally(publicId, room.getId());
        return new VoteRoomResultResponse(room.getStatus().name(), room.getWinnerCandidateId(),
            snapshot.version(), snapshot.entries());
    }

    // STOMP SUBSCRIBE 시점의 인가 + 입장 처리 (설계 §4.2: 방 입장/구독 = JOINED 전환 트리거).
    // ChannelInterceptor는 트랜잭션 밖에서 동작하므로, 참가자 상태 변경은 이 @Transactional 메서드를 거쳐야 한다.
    @Transactional
    public void joinOnSubscribe(String publicId, Long memberId) {
        VoteRoom room = findRoom(publicId);
        VoteParticipant me = voteParticipantRepository
            .findByRoomIdAndMemberIdAndDeletedFalse(room.getId(), memberId)
            .orElseThrow(() -> new EBException(ErrorCode.NOT_ROOM_PARTICIPANT));
        me.join();
    }

    @Transactional
    public VoteRoomResultResponse close(String publicId, Long memberId) {
        // cast()와 같은 방 행 잠금을 공유한다.
        // - 진행 중인 cast가 커밋된 뒤에야 tally를 스냅샷하므로 winner와 최종 집계가 어긋나지 않는다.
        // - 동시 close 이중 호출도 직렬화되어, 늦은 쪽은 아래 멱등 분기로 빠진다 (winner 덮어쓰기/이중 push 차단).
        VoteRoom room = voteRoomRepository.findWithLockByPublicIdAndDeletedFalse(publicId)
            .orElseThrow(() -> new EBException(ErrorCode.VOTE_ROOM_NOT_FOUND));
        if (!room.isHost(memberId)) {
            throw new EBException(ErrorCode.NOT_ROOM_HOST);
        }

        // 멱등: 이미 CLOSED면 기존 winner 그대로 반환 (재계산·재push 없음)
        if (room.isClosed()) {
            TallySnapshot snapshot = voteRoomCacheService.getTally(publicId, room.getId());
            return new VoteRoomResultResponse(room.getStatus().name(), room.getWinnerCandidateId(),
                snapshot.version(), snapshot.entries());
        }

        // version을 증가시킨 스냅샷을 쓴다. 안 그러면 ROOM_CLOSED가 직전 TALLY_UPDATED와 같은 version을 실어
        // 클라이언트의 단조증가 dedup에 폐기되고 참가자 화면이 CLOSED로 안 바뀐다.
        TallySnapshot snapshot = voteRoomCacheService.bumpVersionAndGetTally(publicId, room.getId());
        room.close(decideWinner(snapshot.entries()));

        // 실제 OPEN -> CLOSED 전환 시에만 커밋 후 broadcast — 멱등 경로(위 early return)는 재push 금지.
        voteRoomBroadcaster.broadcastRoomClosed(publicId, room.getWinnerCandidateId(), snapshot);

        return new VoteRoomResultResponse(room.getStatus().name(), room.getWinnerCandidateId(),
            snapshot.version(), snapshot.entries());
    }

    // D2: 최고점, 동점이면 최소 candidateId 승리 (완전 결정론). score = 순위 ballot 점수합.
    private Long decideWinner(List<TallyEntry> tally) {
        return tally.stream()
            .max(Comparator.comparing(TallyEntry::score)
                .thenComparing(Comparator.comparing(TallyEntry::candidateId).reversed()))
            .map(TallyEntry::candidateId)
            .orElse(null);
    }

    private VoteRoom findRoom(String publicId) {
        return voteRoomRepository.findByPublicIdAndDeletedFalse(publicId)
            .orElseThrow(() -> new EBException(ErrorCode.VOTE_ROOM_NOT_FOUND));
    }

    private void validateParticipant(Long roomId, Long memberId) {
        if (!voteParticipantRepository.existsByRoomIdAndMemberIdAndDeletedFalse(roomId, memberId)) {
            throw new EBException(ErrorCode.NOT_ROOM_PARTICIPANT);
        }
    }

    private String generatePublicId() {
        return "VR_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // 6자리 대문자+숫자. 혼동문자(0,O,1,I,L) 제외. 충돌(unique) 시 재생성.
    private String generateInviteCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(INVITE_CODE_LENGTH);
            for (int i = 0; i < INVITE_CODE_LENGTH; i++) {
                sb.append(INVITE_CODE_ALPHABET.charAt(
                    INVITE_CODE_RANDOM.nextInt(INVITE_CODE_ALPHABET.length())));
            }
            code = sb.toString();
        } while (voteRoomRepository.findByInviteCodeAndDeletedFalse(code).isPresent());
        return code;
    }
}
