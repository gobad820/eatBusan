package com.ssafy.eatBusan.voteroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.ssafy.eatBusan.global.exception.EBException;
import com.ssafy.eatBusan.global.exception.ErrorCode;
import com.ssafy.eatBusan.place.apiUtil.KakaoApiUtil;
import com.ssafy.eatBusan.place.apiUtil.dto.KakaoPlaceResponse;
import com.ssafy.eatBusan.place.apiUtil.dto.KakaoSearchResponse;
import com.ssafy.eatBusan.voteroom.domain.Vote;
import com.ssafy.eatBusan.voteroom.dto.CandidateResponse;
import com.ssafy.eatBusan.voteroom.dto.TallyEntry;
import com.ssafy.eatBusan.voteroom.dto.VoteResponse;
import com.ssafy.eatBusan.voteroom.dto.VoteRoomCreateRequest;
import com.ssafy.eatBusan.voteroom.dto.VoteRoomCreateResponse;
import com.ssafy.eatBusan.voteroom.dto.VoteRoomDetailResponse;
import com.ssafy.eatBusan.voteroom.dto.VoteRoomResultResponse;
import com.ssafy.eatBusan.voteroom.domain.VoteRoom;
import com.ssafy.eatBusan.voteroom.repository.VoteCandidateRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteParticipantRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRoomRepository;
import com.ssafy.eatBusan.voteroom.service.VoteRoomCleanupService;
import com.ssafy.eatBusan.voteroom.service.VoteRoomService;
import com.ssafy.eatBusan.voteroom.service.VoteService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 투표방 순위투표 통합 검증.
 *
 * <p>모델: 한 투표자가 후보 1~3개를 순서대로 선택(ballot). 점수 매핑 rank1=5, rank2=3, rank3=1.
 * 집계(tally)는 후보별 점수 합, 승자는 최고점·동점 시 최소 candidateId.
 *
 * <ul>
 *   <li>DB: H2 (테스트 프로퍼티), Redis: 실제 localhost 인스턴스 (PostLike 패턴 전제와 동일)
 *   <li>KakaoApiUtil만 가짜 응답으로 대체 — 방 생성 시 후보 시드 경로를 외부 의존 없이 재현한다.
 *   <li>각 케이스는 "응답 + DB + Redis" 검증과 불변(안 바뀌어야 할 집계) 검증을 함께 수행한다.
 * </ul>
 */
@SpringBootTest
class VoteRoomIntegrationTest {

    private static final Long HOST = 9100L;
    private static final Long MEMBER_A = 9101L;
    private static final Long MEMBER_B = 9102L;
    private static final Long OUTSIDER = 9103L;

    // 순위→점수 매핑(SSOT). vote-cast.lua / Vote.pointsOf 와 동일.
    private static final long RANK1 = 5L;
    private static final long RANK2 = 3L;
    private static final long RANK3 = 1L;

    // 테스트 간 Place.code 충돌을 피하기 위한 전역 증가 카운터
    private static final AtomicLong PLACE_CODE_SEQ = new AtomicLong(910_000_000L);

    @Autowired
    private VoteRoomService voteRoomService;
    @Autowired
    private VoteService voteService;
    @Autowired
    private VoteRoomCleanupService voteRoomCleanupService;
    @Autowired
    private VoteRepository voteRepository;
    @Autowired
    private VoteRoomRepository voteRoomRepository;
    @Autowired
    private VoteCandidateRepository voteCandidateRepository;
    @Autowired
    private VoteParticipantRepository voteParticipantRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private KakaoApiUtil kakaoApiUtil;

    private final List<String> createdPublicIds = new ArrayList<>();

    @AfterEach
    void cleanUpRedis() {
        // publicId가 방마다 랜덤이라 충돌은 없지만, 공유 Redis에 테스트 키를 남기지 않는다.
        for (String publicId : createdPublicIds) {
            Set<String> keys = redisTemplate.keys("voteroom:" + publicId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
        createdPublicIds.clear();
    }

    private VoteRoomCreateResponse createRoom() {
        given(kakaoApiUtil.searchPlaces(any())).willReturn(fakeKakaoResponse(5));
        VoteRoomCreateResponse room = voteRoomService.create(HOST, new VoteRoomCreateRequest(
                "순위투표 테스트 점심", 35.2322, 129.0838, 1000));
        createdPublicIds.add(room.roomPublicId());
        return room;
    }

    private KakaoSearchResponse fakeKakaoResponse(int count) {
        List<KakaoPlaceResponse> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long code = PLACE_CODE_SEQ.incrementAndGet();
            docs.add(new KakaoPlaceResponse(String.valueOf(code), "가짜식당" + code,
                    "http://place.test/" + code, "부산 금정구 테스트로 " + i, "051-000-0000",
                    129.08 + i * 0.001, 35.23 + i * 0.001));
        }
        return new KakaoSearchResponse(docs);
    }

    private Map<Long, Long> tallyMap(List<TallyEntry> tally) {
        return tally.stream().collect(Collectors.toMap(TallyEntry::candidateId, TallyEntry::score));
    }

    private long totalScore(List<TallyEntry> tally) {
        return tally.stream().mapToLong(TallyEntry::score).sum();
    }

    private Long roomId(String publicId) {
        return voteRoomRepository.findByPublicIdAndDeletedFalse(publicId).orElseThrow().getId();
    }

    // 후보 candidateId 오름차순 정렬 — 동점 tie-break(최소 candidateId) 기대값 산출용.
    private List<Long> sortedCandidateIds(VoteRoomCreateResponse room) {
        return room.candidates().stream()
                .map(CandidateResponse::candidateId)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("방 생성 — inviteCode 발급, 후보 5개 시드, 호스트만 JOINED, Redis tally 0점 시드")
    void createRoom_seedsCandidatesAndHostOnly() {
        VoteRoomCreateResponse room = createRoom();

        assertSoftly(softly -> {
            softly.assertThat(room.roomPublicId()).startsWith("VR_");
            softly.assertThat(room.inviteCode()).matches("[A-Z2-9]{6}");
            softly.assertThat(room.candidates()).hasSize(5);
            // 호스트만 참가자(JOINED). 초대 멤버 사전 등록 없음.
            softly.assertThat(room.participants()).hasSize(1);
            softly.assertThat(room.participants().get(0).memberId()).isEqualTo(HOST);
            softly.assertThat(room.participants().get(0).status()).isEqualTo("JOINED");
        });

        // Redis: 0점 후보도 전부 tally에 존재해야 한다 (ZADD 0 시드)
        Set<String> members = redisTemplate.opsForZSet()
                .range("voteroom:" + room.roomPublicId() + ":tally", 0, -1);
        assertThat(members).containsExactlyInAnyOrderElementsOf(
                room.candidates().stream().map(c -> String.valueOf(c.candidateId())).toList());
    }

    @Test
    @DisplayName("코드 입장 — 올바른 코드로 join 시 참가자 추가(JOINED), 잘못된 코드는 404 거부")
    void join_byInviteCode() {
        VoteRoomCreateResponse room = createRoom();

        VoteRoomDetailResponse joined = voteRoomService.join(room.inviteCode(), MEMBER_A);

        assertSoftly(softly -> {
            softly.assertThat(joined.roomPublicId()).isEqualTo(room.roomPublicId());
            softly.assertThat(joined.participants()).hasSize(2);
            softly.assertThat(joined.participants())
                    .filteredOn(p -> p.memberId().equals(MEMBER_A))
                    .allMatch(p -> p.status().equals("JOINED"));
        });
        // DB: 참가자 2명(HOST, MEMBER_A)
        assertThat(voteParticipantRepository.findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId())))
                .hasSize(2);

        // 같은 멤버 재입장은 멱등 — 참가자 수 불변
        voteRoomService.join(room.inviteCode(), MEMBER_A);
        assertThat(voteParticipantRepository.findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId())))
                .hasSize(2);

        // 잘못된 코드 → 404
        EBException e = assertThrows(EBException.class,
                () -> voteRoomService.join("ZZZZZZ", MEMBER_B));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INVITE_CODE);
    }

    @Test
    @DisplayName("순위투표 — ballot=[c1,c2,c3] → c1+5,c2+3,c3+1. 응답 myBallot/tally + DB votes 3행(rank 1/2/3)")
    void rankedVote_assignsScoresAndInsertsThreeRows() {
        VoteRoomCreateResponse room = createRoom();
        List<Long> ids = sortedCandidateIds(room);
        Long c1 = ids.get(0);
        Long c2 = ids.get(1);
        Long c3 = ids.get(2);

        VoteResponse response = voteService.cast(room.roomPublicId(), HOST, List.of(c1, c2, c3));

        Map<Long, Long> tally = tallyMap(response.tally());
        assertSoftly(softly -> {
            softly.assertThat(response.myBallot()).containsExactly(c1, c2, c3); // 순서 보존
            softly.assertThat(tally.get(c1)).isEqualTo(RANK1);
            softly.assertThat(tally.get(c2)).isEqualTo(RANK2);
            softly.assertThat(tally.get(c3)).isEqualTo(RANK3);
            softly.assertThat(response.tally()).hasSize(5);           // 0점 후보 포함
            softly.assertThat(totalScore(response.tally())).isEqualTo(RANK1 + RANK2 + RANK3);
            softly.assertThat(response.votedCount()).isEqualTo(1L);   // 투표 완료 1명(HOST)
        });

        // DB: rank 1/2/3 세 행
        List<Vote> votes = voteRepository
                .findAllByRoomIdAndMemberIdAndDeletedFalseOrderByRankAsc(roomId(room.roomPublicId()), HOST);
        assertSoftly(softly -> {
            softly.assertThat(votes).hasSize(3);
            softly.assertThat(votes.get(0).getRank()).isEqualTo(1);
            softly.assertThat(votes.get(0).getCandidateId()).isEqualTo(c1);
            softly.assertThat(votes.get(1).getRank()).isEqualTo(2);
            softly.assertThat(votes.get(1).getCandidateId()).isEqualTo(c2);
            softly.assertThat(votes.get(2).getRank()).isEqualTo(3);
            softly.assertThat(votes.get(2).getCandidateId()).isEqualTo(c3);
        });

        // Redis: tally ZSET score 직접 확인
        assertThat(redisTemplate.opsForZSet()
                .score("voteroom:" + room.roomPublicId() + ":tally", String.valueOf(c1)))
                .isEqualTo((double) RANK1);
        assertThat(redisTemplate.opsForValue()
                .get("voteroom:" + room.roomPublicId() + ":ballot:" + HOST))
                .isEqualTo(c1 + "," + c2 + "," + c3);
    }

    @Test
    @DisplayName("ballot 교체(재투표) — 이전 점수 차감 + 새 점수 가산, 동일 ballot 재제출은 멱등(version 불변)")
    void changeBallot_recomputesScores_andSameBallotIsIdempotent() {
        VoteRoomCreateResponse room = createRoom();
        List<Long> ids = sortedCandidateIds(room);
        Long c1 = ids.get(0);
        Long c2 = ids.get(1);
        Long c3 = ids.get(2);
        Long c4 = ids.get(3);
        String verKey = "voteroom:" + room.roomPublicId() + ":ver";

        // 첫 ballot: [c1,c2,c3]
        voteService.cast(room.roomPublicId(), HOST, List.of(c1, c2, c3));

        // 새 ballot: [c2,c4] → c2 rank1=5, c4 rank2=3. 이전 점수(c1=5,c2=3,c3=1) 전부 차감.
        VoteResponse changed = voteService.cast(room.roomPublicId(), HOST, List.of(c2, c4));
        Map<Long, Long> tally = tallyMap(changed.tally());
        assertSoftly(softly -> {
            softly.assertThat(changed.myBallot()).containsExactly(c2, c4);
            softly.assertThat(tally.get(c1)).isEqualTo(0L);     // 차감됨
            softly.assertThat(tally.get(c2)).isEqualTo(RANK1);  // 3 → 0 → +5
            softly.assertThat(tally.get(c3)).isEqualTo(0L);     // 차감됨
            softly.assertThat(tally.get(c4)).isEqualTo(RANK2);
            softly.assertThat(totalScore(changed.tally())).isEqualTo(RANK1 + RANK2);
            // ballot 교체(재투표)해도 distinct 투표자 수는 불변 — 여전히 1명(HOST).
            softly.assertThat(changed.votedCount()).isEqualTo(1L);
        });
        // DB: 교체 후 2행(이전 3행 물리 삭제됨)
        assertThat(voteRepository
                .findAllByRoomIdAndMemberIdAndDeletedFalseOrderByRankAsc(roomId(room.roomPublicId()), HOST))
                .hasSize(2);

        // 동일 ballot 재제출 → 멱등: tally 불변 + version 불변
        long verBefore = Long.parseLong(redisTemplate.opsForValue().get(verKey));
        VoteResponse same = voteService.cast(room.roomPublicId(), HOST, List.of(c2, c4));
        long verAfter = Long.parseLong(redisTemplate.opsForValue().get(verKey));
        assertSoftly(softly -> {
            softly.assertThat(same.myBallot()).containsExactly(c2, c4);
            softly.assertThat(tallyMap(same.tally())).isEqualTo(tally);
            softly.assertThat(verAfter).isEqualTo(verBefore);  // 멱등이면 버전 단조 증가가 멈춘다
            softly.assertThat(same.votedCount()).isEqualTo(1L); // 동일 ballot 재제출도 1명 불변
        });
        assertThat(voteRepository
                .findAllByRoomIdAndMemberIdAndDeletedFalseOrderByRankAsc(roomId(room.roomPublicId()), HOST))
                .hasSize(2);
    }

    @Test
    @DisplayName("승자 — 최고점 승리, 동점 시 최소 candidateId")
    void winner_highestScoreThenSmallestId() {
        // 1) 명확한 최고점 승자
        VoteRoomCreateResponse room = createRoom();
        List<Long> ids = sortedCandidateIds(room);
        // HOST: [c1] → c1+5. A: [c0] → c0+5 이지만 아래 구성으로 c0가 단독 최고가 되게 함.
        voteRoomService.join(room.inviteCode(), MEMBER_A);
        voteService.cast(room.roomPublicId(), HOST, List.of(ids.get(0), ids.get(1))); // c0=5, c1=3
        voteService.cast(room.roomPublicId(), MEMBER_A, List.of(ids.get(0)));         // c0=10
        VoteRoomResultResponse closed = voteRoomService.close(room.roomPublicId(), HOST);
        assertThat(closed.winnerCandidateId()).isEqualTo(ids.get(0)); // 최고점 c0=10
        // 서로 다른 2명(HOST, MEMBER_A)이 투표 완료 → close 응답 votedCount==2
        assertThat(closed.votedCount()).isEqualTo(2L);

        // 2) 동점 → 최소 candidateId
        VoteRoomCreateResponse tieRoom = createRoom();
        List<Long> tieIds = sortedCandidateIds(tieRoom);
        voteRoomService.join(tieRoom.inviteCode(), MEMBER_A);
        // host=[c2](5), A=[c1](5) → c1,c2 동점 5점. 승자는 최소 candidateId c1.
        voteService.cast(tieRoom.roomPublicId(), HOST, List.of(tieIds.get(2)));
        voteService.cast(tieRoom.roomPublicId(), MEMBER_A, List.of(tieIds.get(1)));
        VoteRoomResultResponse tieClosed = voteRoomService.close(tieRoom.roomPublicId(), HOST);
        Long expectedTieWinner = tieIds.stream()
                .filter(id -> id.equals(tieIds.get(1)) || id.equals(tieIds.get(2)))
                .min(Comparator.naturalOrder()).orElseThrow();
        assertThat(tieClosed.winnerCandidateId()).isEqualTo(expectedTieWinner);
    }

    @Test
    @DisplayName("마감 — close 시 status CLOSED, winner 확정, closedAt 기록. 비호스트는 403")
    void close_setsStatusWinnerAndClosedAt() {
        VoteRoomCreateResponse room = createRoom();
        List<Long> ids = sortedCandidateIds(room);
        voteService.cast(room.roomPublicId(), HOST, List.of(ids.get(0)));

        // 마감 직전 version 스냅샷(vPre). close가 version을 증가시켜야 참가자 화면 dedup이 통과한다.
        long vPre = voteRoomService.getResult(room.roomPublicId(), HOST).version();

        // 비호스트 마감 → 403, 상태 불변
        EBException notHost = assertThrows(EBException.class,
                () -> voteRoomService.close(room.roomPublicId(), MEMBER_A));
        assertThat(notHost.getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_HOST);

        VoteRoomResultResponse closed = voteRoomService.close(room.roomPublicId(), HOST);
        assertSoftly(softly -> {
            softly.assertThat(closed.status()).isEqualTo("CLOSED");
            softly.assertThat(closed.winnerCandidateId()).isEqualTo(ids.get(0));
            // close는 version을 엄격히 증가시킨다(vClose > vPre). 같으면 ROOM_CLOSED 스냅샷이 폐기되는 버그.
            softly.assertThat(closed.version()).isGreaterThan(vPre);
        });
        // DB: status CLOSED + closedAt 기록
        VoteRoom persisted = voteRoomRepository.findByPublicIdAndDeletedFalse(room.roomPublicId())
                .orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(persisted.isClosed()).isTrue();
            softly.assertThat(persisted.getWinnerCandidateId()).isEqualTo(ids.get(0));
            softly.assertThat(persisted.getClosedAt()).isNotNull();
        });

        // 재마감 멱등
        VoteRoomResultResponse again = voteRoomService.close(room.roomPublicId(), HOST);
        assertThat(again.winnerCandidateId()).isEqualTo(closed.winnerCandidateId());
    }

    @Test
    @DisplayName("단발성 삭제 — 마감 경과 방 hard delete 시 room/vote/candidate/participant 물리 삭제, 이후 조회 404")
    void cleanup_hardDeletesClosedRoom() {
        VoteRoomCreateResponse room = createRoom();
        Long internalId = roomId(room.roomPublicId());
        List<Long> ids = sortedCandidateIds(room);
        voteService.cast(room.roomPublicId(), HOST, List.of(ids.get(0), ids.get(1)));
        voteRoomService.close(room.roomPublicId(), HOST);

        // BEFORE: 모든 연관 엔티티가 존재한다.
        assertSoftly(softly -> {
            softly.assertThat(voteRoomRepository.findById(internalId)).isPresent();
            softly.assertThat(voteRepository.findAllByRoomIdAndDeletedFalse(internalId)).isNotEmpty();
            softly.assertThat(voteCandidateRepository.findAllByRoomIdAndDeletedFalse(internalId)).hasSize(5);
            softly.assertThat(voteParticipantRepository.findAllByRoomIdAndDeletedFalse(internalId)).isNotEmpty();
        });

        // delete-delay 경과를 시뮬레이션: 미래 시각을 threshold로 줘 closedAt(now)을 만료로 본다.
        List<VoteRoom> expired = voteRoomCleanupService.findExpiredRooms(LocalDateTime.now().plusMinutes(1));
        assertThat(expired).extracting(VoteRoom::getId).contains(internalId);
        VoteRoom target = expired.stream().filter(r -> r.getId().equals(internalId)).findFirst().orElseThrow();
        voteRoomCleanupService.cleanup(target);

        // AFTER: room/vote/candidate/participant 물리 삭제
        assertSoftly(softly -> {
            softly.assertThat(voteRoomRepository.findById(internalId)).isEmpty();
            softly.assertThat(voteRepository.findAllByRoomIdAndDeletedFalse(internalId)).isEmpty();
            softly.assertThat(voteCandidateRepository.findAllByRoomIdAndDeletedFalse(internalId)).isEmpty();
            softly.assertThat(voteParticipantRepository.findAllByRoomIdAndDeletedFalse(internalId)).isEmpty();
        });
        // 조회 404 (Redis 키 흔적은 남기지 않음 — cleanup이 purge)
        EBException e = assertThrows(EBException.class,
                () -> voteRoomService.getDetail(room.roomPublicId(), HOST));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOTE_ROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("인가 — 비참가자 투표/조회 거부(403), 집계 불변")
    void nonParticipant_voteAndReadForbidden() {
        VoteRoomCreateResponse room = createRoom();
        List<Long> ids = sortedCandidateIds(room);
        voteService.cast(room.roomPublicId(), HOST, List.of(ids.get(0)));
        List<TallyEntry> before = voteRoomService.getResult(room.roomPublicId(), HOST).tally();

        EBException voteDenied = assertThrows(EBException.class,
                () -> voteService.cast(room.roomPublicId(), OUTSIDER, List.of(ids.get(0))));
        assertThat(voteDenied.getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_PARTICIPANT);

        EBException readDenied = assertThrows(EBException.class,
                () -> voteRoomService.getResult(room.roomPublicId(), OUTSIDER));
        assertThat(readDenied.getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_PARTICIPANT);

        // 불변: 집계·DB 그대로
        assertThat(tallyMap(voteRoomService.getResult(room.roomPublicId(), HOST).tally()))
                .isEqualTo(tallyMap(before));
        assertThat(voteRepository.findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId())))
                .hasSize(1);
    }

    @Test
    @DisplayName("거부 케이스 — 다른 방 후보(400), 없는 방(404), 빈/중복/초과 ballot(400)")
    void invalidVotes_rejected() {
        VoteRoomCreateResponse roomA = createRoom();
        VoteRoomCreateResponse roomB = createRoom();
        Long foreignCandidate = roomB.candidates().get(0).candidateId();

        EBException wrongCandidate = assertThrows(EBException.class,
                () -> voteService.cast(roomA.roomPublicId(), HOST, List.of(foreignCandidate)));
        assertThat(wrongCandidate.getErrorCode()).isEqualTo(ErrorCode.CANDIDATE_NOT_IN_ROOM);

        EBException noRoom = assertThrows(EBException.class,
                () -> voteService.cast("VR_nope9999", HOST, List.of(foreignCandidate)));
        assertThat(noRoom.getErrorCode()).isEqualTo(ErrorCode.VOTE_ROOM_NOT_FOUND);

        List<Long> ids = sortedCandidateIds(roomA);
        EBException empty = assertThrows(EBException.class,
                () -> voteService.cast(roomA.roomPublicId(), HOST, List.of()));
        assertThat(empty.getErrorCode()).isEqualTo(ErrorCode.BALLOT_EMPTY);

        EBException dup = assertThrows(EBException.class,
                () -> voteService.cast(roomA.roomPublicId(), HOST, List.of(ids.get(0), ids.get(0))));
        assertThat(dup.getErrorCode()).isEqualTo(ErrorCode.BALLOT_DUPLICATE_CANDIDATE);

        EBException tooMany = assertThrows(EBException.class,
                () -> voteService.cast(roomA.roomPublicId(), HOST,
                        List.of(ids.get(0), ids.get(1), ids.get(2), ids.get(3))));
        assertThat(tooMany.getErrorCode()).isEqualTo(ErrorCode.BALLOT_TOO_MANY);
    }

    @Test
    @DisplayName("CLOSED 방 투표 — 409(VOTE_ROOM_CLOSED), 집계·DB 불변")
    void voteOnClosedRoom_conflict() {
        VoteRoomCreateResponse room = createRoom();
        List<Long> ids = sortedCandidateIds(room);
        voteService.cast(room.roomPublicId(), HOST, List.of(ids.get(0)));
        voteRoomService.close(room.roomPublicId(), HOST);
        List<TallyEntry> before = voteRoomService.getResult(room.roomPublicId(), HOST).tally();

        EBException e = assertThrows(EBException.class,
                () -> voteService.cast(room.roomPublicId(), HOST, List.of(ids.get(1))));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOTE_ROOM_CLOSED);

        assertThat(tallyMap(voteRoomService.getResult(room.roomPublicId(), HOST).tally()))
                .isEqualTo(tallyMap(before));
        assertThat(voteRepository
                .findAllByRoomIdAndMemberIdAndDeletedFalseOrderByRankAsc(roomId(room.roomPublicId()), HOST)
                .get(0).getCandidateId()).isEqualTo(ids.get(0));
    }

    @Test
    @DisplayName("Redis 유실 후 조회 — bootstrap이 DB 기준으로 tally/ballot 복원")
    void bootstrap_rebuildsFromDbAfterRedisLoss() {
        VoteRoomCreateResponse room = createRoom();
        List<Long> ids = sortedCandidateIds(room);
        voteRoomService.join(room.inviteCode(), MEMBER_A);
        voteService.cast(room.roomPublicId(), HOST, List.of(ids.get(0), ids.get(1)));    // c0=5, c1=3
        voteService.cast(room.roomPublicId(), MEMBER_A, List.of(ids.get(1)));            // c1+=5 → c1=8

        // Redis 키 전체 유실 시뮬레이션
        Set<String> keys = redisTemplate.keys("voteroom:" + room.roomPublicId() + ":*");
        redisTemplate.delete(keys);

        VoteRoomResultResponse result = voteRoomService.getResult(room.roomPublicId(), HOST);
        Map<Long, Long> tally = tallyMap(result.tally());
        assertSoftly(softly -> {
            softly.assertThat(tally.get(ids.get(0))).isEqualTo(RANK1);          // c0=5
            softly.assertThat(tally.get(ids.get(1))).isEqualTo(RANK2 + RANK1);  // c1=8
            softly.assertThat(result.tally()).hasSize(5);
            // getResult 응답 votedCount = distinct 투표자 2명(HOST, MEMBER_A)
            softly.assertThat(result.votedCount()).isEqualTo(2L);
        });
        // ballot 키도 복원되어 myBallot 조회가 가능해야 한다.
        VoteRoomDetailResponse detail = voteRoomService.getDetail(room.roomPublicId(), HOST);
        assertThat(detail.myBallot()).containsExactly(ids.get(0), ids.get(1));
    }

    @Test
    @DisplayName("votedCount — 투표 0건이면 0, 서로 다른 3명 투표 후 cast/getResult/close 모두 3")
    void votedCount_zeroWhenNoVotes_andDistinctVoterCount() {
        VoteRoomCreateResponse room = createRoom();
        List<Long> ids = sortedCandidateIds(room);
        voteRoomService.join(room.inviteCode(), MEMBER_A);
        voteRoomService.join(room.inviteCode(), MEMBER_B);

        // 투표 0건 → getResult votedCount == 0
        assertThat(voteRoomService.getResult(room.roomPublicId(), HOST).votedCount()).isEqualTo(0L);

        // 서로 다른 3명이 순차 투표 — cast 응답 votedCount가 누적 distinct 인원수와 같다.
        VoteResponse castHost = voteService.cast(room.roomPublicId(), HOST, List.of(ids.get(0)));
        assertThat(castHost.votedCount()).isEqualTo(1L);
        VoteResponse castA = voteService.cast(room.roomPublicId(), MEMBER_A, List.of(ids.get(1)));
        assertThat(castA.votedCount()).isEqualTo(2L);
        VoteResponse castB = voteService.cast(room.roomPublicId(), MEMBER_B, List.of(ids.get(2)));
        assertThat(castB.votedCount()).isEqualTo(3L);

        // getResult도 distinct 3명
        assertThat(voteRoomService.getResult(room.roomPublicId(), HOST).votedCount()).isEqualTo(3L);

        // close 응답에도 votedCount 포함되고 distinct 3명과 같다.
        VoteRoomResultResponse closed = voteRoomService.close(room.roomPublicId(), HOST);
        assertThat(closed.votedCount()).isEqualTo(3L);
    }
}
