local tallyKey  = KEYS[1]   -- voteroom:{publicId}:tally (ZSET, member=candidateId, score=표수)
local choiceKey = KEYS[2]   -- voteroom:{publicId}:choice:{memberId} (값=candidateId)
local newCand   = ARGV[1]   -- 새로 선택한 candidateId (문자열)

local prev = redis.call('GET', choiceKey)

-- 같은 후보 재클릭: 아무것도 바꾸지 않는다 (멱등)
if prev == newCand then
    return {0, prev}
end

-- 표 변경: 이전 후보의 표를 먼저 차감
if prev then
    redis.call('ZINCRBY', tallyKey, -1, prev)
end

-- 새 후보 +1, 내 선택 갱신 (1인 1표)
redis.call('ZINCRBY', tallyKey, 1, newCand)
redis.call('SET', choiceKey, newCand)

return {1, prev or ''}
