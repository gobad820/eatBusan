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
import com.ssafy.eatBusan.voteroom.repository.VoteRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRoomRepository;
import com.ssafy.eatBusan.voteroom.service.VoteRoomService;
import com.ssafy.eatBusan.voteroom.service.VoteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 투표방 E2E 검증 (설계 §11.1 매트릭스).
 *
 * - DB: H2 (테스트 프로퍼티), Redis: 실제 localhost 인스턴스 (PostLike 패턴과 동일 전제)
 * - KakaoApiUtil만 가짜 응답으로 대체 — 방 생성 시 후보 시드 경로를 외부 의존 없이 재현한다.
 * - 각 케이스는 "응답 + DB + Redis" 삼중 검증과 불변(안 바뀌어야 할 집계) 검증을 함께 수행한다.
 */
@SpringBootTest
class VoteRoomIntegrationTest {

    private static final Long HOST = 9100L;
    private static final Long MEMBER_A = 9101L;
    private static final Long MEMBER_B = 9102L;
    private static final Long OUTSIDER = 9103L;

    // 테스트 간 Place.code 충돌을 피하기 위한 전역 증가 카운터
    private static final AtomicLong PLACE_CODE_SEQ = new AtomicLong(910_000_000L);

    @Autowired
    private VoteRoomService voteRoomService;
    @Autowired
    private VoteService voteService;
    @Autowired
    private VoteRepository voteRepository;
    @Autowired
    private VoteRoomRepository voteRoomRepository;
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
                "E2E 테스트 점심", 35.2322, 129.0838, 1000, List.of(MEMBER_A, MEMBER_B)));
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
        return tally.stream().collect(Collectors.toMap(TallyEntry::candidateId, TallyEntry::count));
    }

    private long totalVotes(List<TallyEntry> tally) {
        return tally.stream().mapToLong(TallyEntry::count).sum();
    }

    private Long roomId(String publicId) {
        return voteRoomRepository.findByPublicIdAndDeletedFalse(publicId).orElseThrow().getId();
    }

    @Test
    @DisplayName("방 생성 — 후보 5개 시드, 호스트 JOINED, 초대자 INVITED, Redis tally 0표 시드")
    void createRoom_seedsCandidatesAndParticipants() {
        VoteRoomCreateResponse room = createRoom();

        assertSoftly(softly -> {
            softly.assertThat(room.roomPublicId()).startsWith("VR_");
            softly.assertThat(room.candidates()).hasSize(5);
            softly.assertThat(room.participants()).hasSize(3);
            softly.assertThat(room.participants())
                    .filteredOn(p -> p.memberId().equals(HOST))
                    .allMatch(p -> p.status().equals("JOINED"));
            softly.assertThat(room.participants())
                    .filteredOn(p -> !p.memberId().equals(HOST))
                    .allMatch(p -> p.status().equals("INVITED"));
        });

        // Redis: 0표 후보도 전부 tally에 존재해야 한다 (ZADD 0 시드)
        Set<String> members = redisTemplate.opsForZSet()
                .range("voteroom:" + room.roomPublicId() + ":tally", 0, -1);
        assertThat(members).containsExactlyInAnyOrderElementsOf(
                room.candidates().stream().map(c -> String.valueOf(c.candidateId())).toList());
    }

    @Test
    @DisplayName("첫 투표 — tally +1, Vote row 1개 생성, 0표 후보도 집계에 노출")
    void firstVote_incrementsTallyAndInsertsRow() {
        VoteRoomCreateResponse room = createRoom();
        Long c1 = room.candidates().get(0).candidateId();

        VoteResponse response = voteService.cast(room.roomPublicId(), MEMBER_A, c1);

        Map<Long, Long> tally = tallyMap(response.tally());
        assertSoftly(softly -> {
            softly.assertThat(response.myCandidateId()).isEqualTo(c1);
            softly.assertThat(tally.get(c1)).isEqualTo(1L);
            softly.assertThat(response.tally()).hasSize(5); // 0표 후보 포함
            softly.assertThat(totalVotes(response.tally())).isEqualTo(1L);
        });

        // DB 검증: row 1개 생성
        List<Vote> votes = voteRepository.findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId()));
        assertThat(votes).hasSize(1);
        assertThat(votes.get(0).getCandidateId()).isEqualTo(c1);
        assertThat(votes.get(0).getMemberId()).isEqualTo(MEMBER_A);

        // Redis 검증: tally/choice 키 직접 확인
        assertThat(redisTemplate.opsForZSet()
                .score("voteroom:" + room.roomPublicId() + ":tally", String.valueOf(c1)))
                .isEqualTo(1.0);
        assertThat(redisTemplate.opsForValue()
                .get("voteroom:" + room.roomPublicId() + ":choice:" + MEMBER_A))
                .isEqualTo(String.valueOf(c1));
    }

    @Test
    @DisplayName("표 변경(A→B) — A는 -1, B는 +1, Vote row는 update(총 row 수 불변), 총 표수 불변")
    void changeVote_movesTallyAndUpdatesRow() {
        VoteRoomCreateResponse room = createRoom();
        Long c1 = room.candidates().get(0).candidateId();
        Long c2 = room.candidates().get(1).candidateId();

        voteService.cast(room.roomPublicId(), MEMBER_A, c1);
        Long rowIdBefore = voteRepository
                .findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId())).get(0).getId();

        VoteResponse response = voteService.cast(room.roomPublicId(), MEMBER_A, c2);

        Map<Long, Long> tally = tallyMap(response.tally());
        assertSoftly(softly -> {
            softly.assertThat(tally.get(c1)).isEqualTo(0L);
            softly.assertThat(tally.get(c2)).isEqualTo(1L);
            softly.assertThat(totalVotes(response.tally())).isEqualTo(1L); // 총 표수 불변
        });

        // DB: 새 row insert가 아니라 기존 row update여야 한다.
        List<Vote> votes = voteRepository.findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId()));
        assertThat(votes).hasSize(1);
        assertThat(votes.get(0).getId()).isEqualTo(rowIdBefore);
        assertThat(votes.get(0).getCandidateId()).isEqualTo(c2);
    }

    @Test
    @DisplayName("같은 후보 재클릭 — 멱등: 응답 200 동작이되 집계·DB·Redis 모두 불변")
    void recastSameCandidate_isIdempotent() {
        VoteRoomCreateResponse room = createRoom();
        Long c1 = room.candidates().get(0).candidateId();

        VoteResponse first = voteService.cast(room.roomPublicId(), MEMBER_A, c1);
        VoteResponse second = voteService.cast(room.roomPublicId(), MEMBER_A, c1);

        assertThat(second.myCandidateId()).isEqualTo(c1);
        assertThat(tallyMap(second.tally())).isEqualTo(tallyMap(first.tally())); // 집계 불변
        assertThat(voteRepository.findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId())))
                .hasSize(1);
    }

    @Test
    @DisplayName("비참가자 투표 — 403(NOT_ROOM_PARTICIPANT), 집계 불변")
    void nonParticipantVote_forbiddenAndNoSideEffect() {
        VoteRoomCreateResponse room = createRoom();
        Long c1 = room.candidates().get(0).candidateId();
        voteService.cast(room.roomPublicId(), MEMBER_A, c1);
        List<TallyEntry> before = voteRoomService.getResult(room.roomPublicId(), MEMBER_A).tally();

        EBException e = assertThrows(EBException.class,
                () -> voteService.cast(room.roomPublicId(), OUTSIDER, c1));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_PARTICIPANT);
        // 불변 검증: 집계·DB 모두 그대로
        assertThat(tallyMap(voteRoomService.getResult(room.roomPublicId(), MEMBER_A).tally()))
                .isEqualTo(tallyMap(before));
        assertThat(voteRepository.findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId())))
                .hasSize(1);
    }

    @Test
    @DisplayName("다른 방 후보로 투표 — 400(CANDIDATE_NOT_IN_ROOM), 없는 방 — 404(VOTE_ROOM_NOT_FOUND)")
    void invalidCandidateOrRoom_rejected() {
        VoteRoomCreateResponse roomA = createRoom();
        VoteRoomCreateResponse roomB = createRoom();
        Long foreignCandidate = roomB.candidates().get(0).candidateId();

        EBException wrongCandidate = assertThrows(EBException.class,
                () -> voteService.cast(roomA.roomPublicId(), MEMBER_A, foreignCandidate));
        assertThat(wrongCandidate.getErrorCode()).isEqualTo(ErrorCode.CANDIDATE_NOT_IN_ROOM);

        EBException noRoom = assertThrows(EBException.class,
                () -> voteService.cast("VR_nope9999", MEMBER_A, foreignCandidate));
        assertThat(noRoom.getErrorCode()).isEqualTo(ErrorCode.VOTE_ROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("마감 — 비호스트 403, 호스트 마감 시 동점이면 최소 candidateId 승리(D2), 재호출 멱등")
    void close_hostOnlyTieBreakAndIdempotent() {
        VoteRoomCreateResponse room = createRoom();
        List<CandidateResponse> candidates = room.candidates();
        Long minOfTied = candidates.stream()
                .map(CandidateResponse::candidateId)
                .sorted()
                .limit(3)
                .min(Long::compareTo)
                .orElseThrow();
        List<Long> sorted = candidates.stream().map(CandidateResponse::candidateId).sorted().toList();
        // 3파전 동점 구성: host/A/B가 각각 다른 후보에 1표
        voteService.cast(room.roomPublicId(), HOST, sorted.get(0));
        voteService.cast(room.roomPublicId(), MEMBER_A, sorted.get(1));
        voteService.cast(room.roomPublicId(), MEMBER_B, sorted.get(2));

        // 비호스트 마감 → 403, 상태 불변
        EBException notHost = assertThrows(EBException.class,
                () -> voteRoomService.close(room.roomPublicId(), MEMBER_A));
        assertThat(notHost.getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_HOST);
        assertThat(voteRoomService.getResult(room.roomPublicId(), MEMBER_A).status())
                .isEqualTo("OPEN");

        // 호스트 마감 → CLOSED + 동점 시 최소 candidateId 승리
        VoteRoomResultResponse closed = voteRoomService.close(room.roomPublicId(), HOST);
        assertSoftly(softly -> {
            softly.assertThat(closed.status()).isEqualTo("CLOSED");
            softly.assertThat(closed.winnerCandidateId()).isEqualTo(minOfTied);
        });

        // 재호출 멱등: 같은 winner, 집계 불변
        VoteRoomResultResponse again = voteRoomService.close(room.roomPublicId(), HOST);
        assertThat(again.status()).isEqualTo("CLOSED");
        assertThat(again.winnerCandidateId()).isEqualTo(closed.winnerCandidateId());
        assertThat(tallyMap(again.tally())).isEqualTo(tallyMap(closed.tally()));
    }

    @Test
    @DisplayName("CLOSED 방 투표 — 409(VOTE_ROOM_CLOSED), 집계·DB 불변")
    void voteOnClosedRoom_conflictAndNoSideEffect() {
        VoteRoomCreateResponse room = createRoom();
        Long c1 = room.candidates().get(0).candidateId();
        Long c2 = room.candidates().get(1).candidateId();
        voteService.cast(room.roomPublicId(), MEMBER_A, c1);
        voteRoomService.close(room.roomPublicId(), HOST);
        List<TallyEntry> before = voteRoomService.getResult(room.roomPublicId(), MEMBER_A).tally();

        EBException e = assertThrows(EBException.class,
                () -> voteService.cast(room.roomPublicId(), MEMBER_A, c2));

        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOTE_ROOM_CLOSED);
        assertThat(tallyMap(voteRoomService.getResult(room.roomPublicId(), MEMBER_A).tally()))
                .isEqualTo(tallyMap(before));
        assertThat(voteRepository.findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId()))
                .get(0).getCandidateId()).isEqualTo(c1);
    }

    @Test
    @DisplayName("Redis 유실 후 조회 — ensureBootstrap이 DB 기준으로 tally/choice를 복원")
    void bootstrap_rebuildsFromDbAfterRedisLoss() {
        VoteRoomCreateResponse room = createRoom();
        Long c1 = room.candidates().get(0).candidateId();
        Long c2 = room.candidates().get(1).candidateId();
        voteService.cast(room.roomPublicId(), HOST, c1);
        voteService.cast(room.roomPublicId(), MEMBER_A, c2);
        voteService.cast(room.roomPublicId(), MEMBER_B, c2);

        // Redis 키 전체 유실 시뮬레이션
        Set<String> keys = redisTemplate.keys("voteroom:" + room.roomPublicId() + ":*");
        redisTemplate.delete(keys);

        VoteRoomResultResponse result = voteRoomService.getResult(room.roomPublicId(), MEMBER_A);

        Map<Long, Long> tally = tallyMap(result.tally());
        assertSoftly(softly -> {
            softly.assertThat(tally.get(c1)).isEqualTo(1L);
            softly.assertThat(tally.get(c2)).isEqualTo(2L);
            softly.assertThat(result.tally()).hasSize(5); // 0표 후보 포함 복원
        });
        // choice 키도 복원되어 내 표 조회가 가능해야 한다.
        VoteRoomDetailResponse detail = voteRoomService.getDetail(room.roomPublicId(), MEMBER_A);
        assertThat(detail.myCandidateId()).isEqualTo(c2);
    }

    @Test
    @DisplayName("동시 투표 2건 — 서로 다른 참가자가 동시에 투표해도 총 표수·집계가 정확")
    void concurrentVotes_keepTallyConsistent() throws InterruptedException {
        VoteRoomCreateResponse room = createRoom();
        Long c1 = room.candidates().get(0).candidateId();
        Long c2 = room.candidates().get(1).candidateId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> failures = new ArrayList<>();

        for (Map.Entry<Long, Long> voterAndChoice
                : Map.of(MEMBER_A, c1, MEMBER_B, c2).entrySet()) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    voteService.cast(room.roomPublicId(),
                            voterAndChoice.getKey(), voterAndChoice.getValue());
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(failures).isEmpty();
        List<TallyEntry> tally = voteRoomService.getResult(room.roomPublicId(), HOST).tally();
        Map<Long, Long> tallyByCandidate = tallyMap(tally);
        assertSoftly(softly -> {
            softly.assertThat(totalVotes(tally)).isEqualTo(2L);
            softly.assertThat(tallyByCandidate.get(c1)).isEqualTo(1L);
            softly.assertThat(tallyByCandidate.get(c2)).isEqualTo(1L);
        });

        // DB도 동일해야 한다 (Redis-DB 정합)
        Map<Long, Long> dbVotes = voteRepository
                .findAllByRoomIdAndDeletedFalse(roomId(room.roomPublicId())).stream()
                .collect(Collectors.toMap(Vote::getMemberId, Vote::getCandidateId));
        assertThat(dbVotes).isEqualTo(Map.of(MEMBER_A, c1, MEMBER_B, c2));
    }
}
