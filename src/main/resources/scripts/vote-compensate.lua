local tallyKey  = KEYS[1]   -- voteroom:{publicId}:tally
local ballotKey = KEYS[2]   -- voteroom:{publicId}:ballot:{memberId}
local verKey    = KEYS[3]   -- voteroom:{publicId}:ver
local newBallot = ARGV[1]   -- 되돌릴(방금 cast한) ballot 콤마조인 문자열
local prevBallot= ARGV[2]   -- cast 직전의 ballot 콤마조인 문자열 ('' = 첫 투표였음)

-- 순위→점수 매핑. !! 동기화 필요 !! 자바 Vote.pointsOf / vote-cast.lua 와 동일.
local pts = {5, 3, 1}

-- cast Lua의 불변식: ballot을 prev -> new로 바꾼 쪽이 반드시 new의 점수를 가산한다.
-- 따라서 "내 cast(new 가산)"이 아직 살아 있는 경우는 ballotKey가 여전히 newBallot일 때뿐이다.
-- 그 사이 다른 cast가 ballot을 덮었다면 내 증분은 이미 정리된 것이므로 아무것도 되돌리면 안 된다.
-- (스냅샷 기반 비원자 되돌림은 이중 차감으로 음수 score/유령 점수를 만든다)
local cur = redis.call('GET', ballotKey)
if cur ~= newBallot then
    return 0
end

-- 새 ballot 점수 차감
local i = 1
for cand in string.gmatch(newBallot, '([^,]+)') do
    redis.call('ZINCRBY', tallyKey, -(pts[i] or 0), cand)
    i = i + 1
end

-- 이전 ballot 점수 가산 + ballotKey 복원 (이전이 ''면 DEL)
if prevBallot ~= '' then
    local j = 1
    for cand in string.gmatch(prevBallot, '([^,]+)') do
        redis.call('ZINCRBY', tallyKey, pts[j] or 0, cand)
        j = j + 1
    end
    redis.call('SET', ballotKey, prevBallot)
else
    redis.call('DEL', ballotKey)
end

-- 되돌림도 집계 변경이므로 버전을 올려 이후 스냅샷이 역행으로 버려지지 않게 한다
redis.call('INCR', verKey)
return 1
