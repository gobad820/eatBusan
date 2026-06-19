local tallyKey = KEYS[1]   -- voteroom:{publicId}:tally (ZSET score=순위 ballot 점수합)
local verKey   = KEYS[2]   -- voteroom:{publicId}:ver

-- 버전과 집계를 한 번에(원자적으로) 스냅샷한다.
-- 따로 읽으면 "낡은 버전 + 새 집계" 쌍이 생겨 클라이언트의 역행 스냅샷 판별이 무의미해진다.
local ver   = redis.call('GET', verKey)
local tally = redis.call('ZREVRANGE', tallyKey, 0, -1, 'WITHSCORES')

return {ver or '0', tally}
