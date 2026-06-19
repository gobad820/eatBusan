local tallyKey  = KEYS[1]   -- voteroom:{publicId}:tally (ZSET, member=candidateId, score=점수합)
local ballotKey = KEYS[2]   -- voteroom:{publicId}:ballot:{memberId} (값=콤마조인 후보목록 "c1,c2,c3", rank 순서)
local verKey    = KEYS[3]   -- voteroom:{publicId}:ver (방별 단조 증가 버전 — broadcast 순서 역전 판별용)
-- ARGV[1..n] : 새 ballot 후보들 (rank 순서대로, 1~3개)

-- 순위→점수 매핑. rank1=5, rank2=3, rank3=1.
-- !! 동기화 필요 !! 이 매핑은 자바 Vote.pointsOf 와 동일하게 유지해야 한다.
--                  한쪽만 바꾸면 Redis 집계와 DB fallback 집계가 어긋난다.
local pts = {5, 3, 1}

-- 새 ballot을 콤마조인 문자열로 (ballotKey 저장/비교용)
local newBallot = table.concat(ARGV, ',')

local prev = redis.call('GET', ballotKey)

-- 같은 ballot 재제출: 아무것도 바꾸지 않는다 (멱등)
if prev == newBallot then
    return {0, prev}
end

-- 이전 ballot 점수 차감 (콤마분리, rank 순서대로 -pts[i])
if prev and prev ~= '' then
    local i = 1
    for cand in string.gmatch(prev, '([^,]+)') do
        redis.call('ZINCRBY', tallyKey, -(pts[i] or 0), cand)
        i = i + 1
    end
end

-- 새 ballot 점수 가산 (rank 순서대로 +pts[i])
for i = 1, #ARGV do
    redis.call('ZINCRBY', tallyKey, pts[i] or 0, ARGV[i])
end

redis.call('SET', ballotKey, newBallot)

-- 집계가 실제로 바뀐 경우에만 버전 증가 — 클라이언트는 낡은 버전의 스냅샷을 버린다
redis.call('INCR', verKey)

return {1, prev or ''}
