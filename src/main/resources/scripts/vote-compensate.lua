local tallyKey  = KEYS[1]   -- voteroom:{publicId}:tally
local choiceKey = KEYS[2]   -- voteroom:{publicId}:choice:{memberId}
local verKey    = KEYS[3]   -- voteroom:{publicId}:ver
local newCand   = ARGV[1]   -- 되돌릴(방금 cast한) candidateId
local prevCand  = ARGV[2]   -- cast 직전의 candidateId ('' = 첫 투표였음)

-- cast Lua의 불변식: choice를 X -> Y로 바꾼 쪽이 반드시 X의 표를 차감한다.
-- 따라서 "내 증분(newCand +1)"이 아직 살아 있는 경우는 choice가 여전히 newCand일 때뿐이다.
-- 그 사이 다른 요청이 choice를 바꿨다면 내 증분은 이미 차감된 것이므로 아무것도 되돌리면 안 된다.
-- (스냅샷 기반 비원자 되돌림은 이중 차감으로 음수 score/유령 표를 만든다)
local cur = redis.call('GET', choiceKey)
if cur ~= newCand then
    return 0
end

redis.call('ZINCRBY', tallyKey, -1, newCand)
if prevCand ~= '' then
    redis.call('ZINCRBY', tallyKey, 1, prevCand)
    redis.call('SET', choiceKey, prevCand)
else
    redis.call('DEL', choiceKey)
end

-- 되돌림도 집계 변경이므로 버전을 올려 이후 스냅샷이 역행으로 버려지지 않게 한다
redis.call('INCR', verKey)
return 1
