package com.ssafy.eatBusan.voteroom.service;

import com.ssafy.eatBusan.global.exception.EBException;
import com.ssafy.eatBusan.global.exception.ErrorCode;
import com.ssafy.eatBusan.voteroom.domain.Vote;
import com.ssafy.eatBusan.voteroom.domain.VoteCandidate;
import com.ssafy.eatBusan.voteroom.dto.TallyEntry;
import com.ssafy.eatBusan.voteroom.repository.VoteCandidateRepository;
import com.ssafy.eatBusan.voteroom.repository.VoteRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VoteRoomCacheService {

    // DB fallback 등 버전을 알 수 없는 집계에 쓰는 값. 클라이언트는 0을 "버전 미상 — 항상 적용"으로 다룬다.
    public static final long UNVERSIONED = 0L;

    // initKey 유효기간. Redis 연결 장애 fallback으로 DB에만 쌓인 표가 있어도
    // 이 시간 안에 initKey가 만료되면 ensureBootstrap이 DB 기준으로 다시 적재해 자가 치유된다.
    // (영구 initKey는 "데이터 유실 없는 연결 단절" 복구 후 영원히 비동기화 상태로 남는다)
    private static final Duration BOOTSTRAP_INIT_TTL = Duration.ofMinutes(10);

    private final VoteCandidateRepository voteCandidateRepository;
    private final VoteRepository voteRepository;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> voteCastScript;
    private final DefaultRedisScript<Long> voteCompensateScript;
    private final DefaultRedisScript<List> voteTallyScript;

    // cast Lua의 결과. changed=false면 같은 후보 재클릭(멱등)이라 Redis가 안 바뀐 것이다.
    public record CastResult(boolean changed, Long prevCandidateId) {
    }

    // 집계 스냅샷 + 방별 단조 증가 버전. broadcast 순서가 커밋 순서와 어긋나도
    // 클라이언트가 버전으로 역행(stale) 스냅샷을 버릴 수 있게 한다.
    public record TallySnapshot(long version, List<TallyEntry> entries) {
    }

    // 방 생성 직후 모든 후보를 0표로 적재한다.
    // ZSET은 ZINCRBY되지 않은 멤버를 갖지 않으므로, 0으로 미리 ZADD해야 0표 후보도 집계에 나타난다.
    public void seed(String publicId, List<Long> candidateIds) {
        redisTemplate.delete(tallyKey(publicId));
        for (Long candidateId : candidateIds) {
            redisTemplate.opsForZSet().add(tallyKey(publicId), String.valueOf(candidateId), 0);
        }
        redisTemplate.opsForValue().set(initKey(publicId), "1", BOOTSTRAP_INIT_TTL);
    }

    public void ensureBootstrap(String publicId, Long roomId) {
        // initKey는 "DB -> Redis 적재가 끝났다"는 완료 표시다.
        // 이 키가 있으면 tally ZSET을 바로 읽거나 cast해도 된다.
        if (Boolean.TRUE.equals(redisTemplate.hasKey(initKey(publicId)))) {
            return;
        }

        // lockKey는 "누군가 지금 bootstrap 중이다"는 작업 중 표시다.
        // setIfAbsent는 Redis SET NX와 같아서, 여러 요청 중 하나만 lock을 잡는다.
        Boolean lockAcquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey(publicId), "1", Duration.ofSeconds(10));

        // lock을 못 잡았다는 건 다른 요청이 DB -> Redis 적재 중이라는 뜻이다.
        // 여기서 그냥 return하면 비어 있는 tally에서 cast가 실행될 수 있으므로 완료를 기다린다.
        if (!Boolean.TRUE.equals(lockAcquired)) {
            waitUntilBootstrapped(publicId);
            return;
        }

        try {
            // 이전 bootstrap 실패로 일부 후보만 남아 있을 수 있으므로,
            // DB를 기준으로 다시 만들기 전에 tally 본체를 먼저 비운다.
            redisTemplate.delete(tallyKey(publicId));

            // 1) 모든 후보를 0표로 적재 — 0표 후보 누락 방지
            List<VoteCandidate> candidates = voteCandidateRepository.findAllByRoomIdAndDeletedFalse(roomId);
            for (VoteCandidate candidate : candidates) {
                redisTemplate.opsForZSet()
                    .add(tallyKey(publicId), String.valueOf(candidate.getId()), 0);
            }

            // 2) DB에 저장된 표를 반영 — tally 증분 + 각자의 choice 키 복원
            List<Vote> votes = voteRepository.findAllByRoomIdAndDeletedFalse(roomId);
            for (Vote vote : votes) {
                redisTemplate.opsForZSet()
                    .incrementScore(tallyKey(publicId), String.valueOf(vote.getCandidateId()), 1);
                redisTemplate.opsForValue()
                    .set(choiceKey(publicId, vote.getMemberId()), String.valueOf(vote.getCandidateId()));
            }

            // 표가 0개여도 bootstrap 완료 상태는 표시해야 한다. (TTL — 위 상수 주석 참고)
            redisTemplate.opsForValue().set(initKey(publicId), "1", BOOTSTRAP_INIT_TTL);
        } finally {
            // bootstrap 성공/실패와 상관없이 작업 중 표시는 반드시 해제한다.
            // initKey는 성공한 경우에만 위에서 세팅한다.
            redisTemplate.delete(lockKey(publicId));
        }
    }

    // Lua로 "이전 표 차감 + 새 표 +1 + 내 선택 갱신"을 원자 처리한다 (1인 1표).
    public CastResult cast(String publicId, Long memberId, Long candidateId) {
        List<?> result = redisTemplate.execute(
            voteCastScript,
            List.of(tallyKey(publicId), choiceKey(publicId, memberId), versionKey(publicId)),
            String.valueOf(candidateId)
        );
        boolean changed = ((Number) result.get(0)).longValue() == 1L;
        String prevRaw = result.size() > 1 && result.get(1) != null ? result.get(1).toString() : "";
        Long prevCandidateId = prevRaw.isEmpty() ? null : Long.valueOf(prevRaw);
        return new CastResult(changed, prevCandidateId);
    }

    // DB sync 실패 시 cast로 이미 바뀐 Redis를 이전 상태로 되돌린다.
    // cast Lua의 정확한 역연산은 "choice가 아직 내 newCandidateId일 때"만 성립한다 —
    // 그 사이 다른 요청이 choice를 바꿨다면 내 증분은 이미 차감된 것이라 되돌리면 안 된다.
    // 조건 검사와 되돌림을 Lua로 원자 처리해 이중 차감(음수 score/유령 표)을 막는다.
    public void compensate(String publicId, Long memberId, Long prevCandidateId, Long newCandidateId) {
        redisTemplate.execute(
            voteCompensateScript,
            List.of(tallyKey(publicId), choiceKey(publicId, memberId), versionKey(publicId)),
            String.valueOf(newCandidateId),
            prevCandidateId != null ? String.valueOf(prevCandidateId) : ""
        );
    }

    // Redis 연결 장애 fallback 직후 best-effort로 initKey를 무효화한다.
    // 성공하면 복구 즉시 bootstrap이 DB 기준으로 재적재되고, 실패해도 TTL 만료로 결국 수렴한다.
    public void tryInvalidateBootstrap(String publicId) {
        try {
            redisTemplate.delete(initKey(publicId));
        } catch (Exception ignored) {
            // 장애 중이면 삭제 자체도 실패할 수 있다 — TTL이 수렴을 보장하므로 무시한다.
        }
    }

    // 현재 집계를 표수 내림차순으로, 방 버전과 함께 원자적으로 스냅샷한다. Redis 다운 시 DB로 fallback.
    public TallySnapshot getTally(String publicId, Long roomId) {
        try {
            ensureBootstrap(publicId, roomId);
            // 버전(GET ver)과 집계(ZREVRANGE)를 한 Lua에서 함께 읽는다.
            // 따로 읽으면 "낡은 버전 + 새 집계" 쌍이 생겨 클라이언트의 역행 판별이 무의미해진다.
            List<?> result = redisTemplate.execute(
                voteTallyScript,
                List.of(tallyKey(publicId), versionKey(publicId))
            );
            if (result == null || result.size() < 2) {
                return new TallySnapshot(UNVERSIONED, List.of());
            }
            long version = Long.parseLong(String.valueOf(result.get(0)));
            List<?> flat = (List<?>) result.get(1);
            List<TallyEntry> entries = new ArrayList<>();
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                entries.add(new TallyEntry(
                    Long.valueOf(String.valueOf(flat.get(i))),
                    (long) Double.parseDouble(String.valueOf(flat.get(i + 1)))));
            }
            return new TallySnapshot(version, entries);
        } catch (RedisConnectionFailureException e) {
            return new TallySnapshot(UNVERSIONED, tallyFromDb(roomId));
        }
    }

    // 내가 현재 찍은 후보. 아직 안 찍었으면 null. Redis 다운 시 DB로 fallback.
    public Long getMyChoice(String publicId, Long roomId, Long memberId) {
        try {
            ensureBootstrap(publicId, roomId);
            String value = redisTemplate.opsForValue().get(choiceKey(publicId, memberId));
            return value != null ? Long.valueOf(value) : null;
        } catch (RedisConnectionFailureException e) {
            return voteRepository.findByRoomIdAndMemberIdAndDeletedFalse(roomId, memberId)
                .map(Vote::getCandidateId)
                .orElse(null);
        }
    }

    // DB 기준 집계. GROUP BY 결과에 없는 0표 후보를 0으로 채워서 반환한다.
    public List<TallyEntry> tallyFromDb(Long roomId) {
        Map<Long, Long> counts = new HashMap<>();
        for (TallyEntry entry : voteRepository.countTallyByRoomId(roomId)) {
            counts.put(entry.candidateId(), entry.count());
        }
        return voteCandidateRepository.findAllByRoomIdAndDeletedFalse(roomId).stream()
            .map(candidate -> new TallyEntry(
                candidate.getId(), counts.getOrDefault(candidate.getId(), 0L)))
            .sorted(Comparator.comparing(TallyEntry::count).reversed()
                .thenComparing(TallyEntry::candidateId))
            .toList();
    }

    private String tallyKey(String publicId) {
        return "voteroom:" + publicId + ":tally";
    }

    private String choiceKey(String publicId, Long memberId) {
        return "voteroom:" + publicId + ":choice:" + memberId;
    }

    private String versionKey(String publicId) {
        return "voteroom:" + publicId + ":ver";
    }

    private String initKey(String publicId) {
        return "voteroom:" + publicId + ":bootstrap:init";
    }

    private String lockKey(String publicId) {
        return "voteroom:" + publicId + ":bootstrap:lock";
    }

    private void waitUntilBootstrapped(String publicId) {
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(100);
                // 다른 요청이 initKey를 세팅했다면 bootstrap이 끝난 것이므로 통과한다.
                if (Boolean.TRUE.equals(redisTemplate.hasKey(initKey(publicId)))) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EBException(ErrorCode.REDIS_BOOTSTRAP_TIMEOUT);
            }
        }

        // 끝까지 initKey가 생기지 않으면 tally ZSET을 신뢰할 수 없다.
        // 이 상태에서 cast를 계속하면 DB와 Redis가 갈라질 수 있으므로 실패시킨다.
        throw new EBException(ErrorCode.REDIS_BOOTSTRAP_TIMEOUT);
    }
}
