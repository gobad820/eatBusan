# 실시간 투표방 (Vote Room) 설계 문서

> **목적**: 초대한 사람들끼리 실시간으로 투표해서 "오늘 갈 맛집 한 곳"을 정하는 기능의 설계 SSOT.
> **상태**: 설계 확정(코드 미착수). 이 문서를 fork 떠서 구현 진행 예정.
> **작성 기준**: `agent-dev-methodology-guide.md` (가정 명시 → 성공 기준 합의 → 외과수술식 변경 → 응답+DB 이중검증).

---

## 0. 한 줄 요약

> 호스트가 **현재 위치 주변 맛집을 후보로 시드한 투표방**을 만들고 사람을 초대 → 참가자는 **1인 1표(변경 가능)** → 투표는 **REST로 쓰고 Redis로 원자 집계** → 결과는 **WebSocket(STOMP) topic으로 실시간 broadcast** → **호스트가 버튼으로 마감**하면 최다득표 맛집 확정.

---

## 1. 확정된 결정 (제품 범위)

| # | 항목 | 결정 | 비고 |
|---|------|------|------|
| 1 | 실시간 전송 | **WebSocket + STOMP** | 방 = topic 매핑. 단일 인스턴스 인메모리 브로커로 시작 |
| 2 | 투표 방식 | **1인 1표 (변경 가능)** | 기존 PostLike 토글 패턴과 동형 → Redis 로직 재사용 |
| 3 | 후보 출처 | **방 생성 시점 호스트의 현재 위치 기반 주변 맛집** | 기존 `PlaceService.searchPlace()` 재사용, 새 Kakao 연동 불필요 |
| 4 | 마감 주체 | **호스트가 버튼으로 마감** | 멱등 처리. deadline 자동마감은 후속(범위 외) |

### 미해결 결정 (구현 중 확정)
- **D1. 후보 개수**: 주변 맛집 중 몇 개를 후보로 시드할지 (제안: 상위 `5~8`개, 거리순).
- **D2. 동점 규칙**: 최다득표 동점 시 (제안 A: 먼저 그 표수에 도달한 후보 / 제안 B: 호스트가 결선 선택). → 1차 구현은 **A(먼저 도달)** 로 단순화 권장.
- **D3. 초대 방식**: memberId 직접 지정 / 초대 코드(링크) 중 무엇으로. → 1차는 **memberId 리스트**로 단순화 권장.

---

## 2. 핵심 통찰 (왜 이 설계인가)

1. **"실시간"의 본질은 broadcast 하나뿐이다.**
   - 투표 *쓰기*(POST)는 0.1초 늦어도 됨 → 기존 REST 레이어(JWT·트랜잭션·Validator) 그대로 사용.
   - *남의 투표를 내가 보는 것*만 실시간 → 방 단위 pub/sub = STOMP topic.
2. **투표 = "좋아요"의 사촌이다.** 1인 1표(변경 가능)은 PostLike 토글과 구조가 같다.
   `Redis Lua 원자 처리 → DB sync → 실패 시 compensate` 패턴을 **그대로 재사용**한다. (`postlike/service/PostLikeService.java`, `PostLikeCacheService.java` 참고)
3. **후보 시드는 이미 구현돼 있다.** `POST /api/places/search` = `PlaceRequestDto(x=경도, y=위도, radius)` → Kakao 주변 맛집. 방 생성에서 이걸 호출만 하면 된다.
4. **WebSocket은 "알림 채널"로만 쓴다.** 투표 쓰기를 `@MessageMapping`으로 받으면 `JwtFilter`/`@LoginMember`/`@Transactional`/Validator가 모두 무력화된다. 쓰기는 REST, push만 STOMP.

---

## 3. 아키텍처 (한 장)

```
[방 생성] POST /api/vote-rooms { title, lat, lng, radius, invitedMemberIds[] }
   │  @LoginMember (host)
   ▼
[VoteRoomService] @Transactional
   ├─ PlaceService.searchPlace(PlaceRequestDto(lng, lat, radius))   ← 기존 재사용
   ├─ 상위 N개 → VoteCandidate 시드
   ├─ invitedMemberIds → VoteParticipant(INVITED)
   └─ VoteRoom(status=OPEN, host) 저장
   → 201 { roomPublicId, candidates[], participants[] }

[투표] POST /api/vote-rooms/{publicId}/votes { candidateId }
   │  @LoginMember (voter)
   ▼
[VoteService] @Transactional
   ├─ VoteValidator: 방 OPEN? 나는 참가자? candidate가 이 방 소속?
   ├─ Redis Lua: 이전 표 차감 + 새 표 +1 (원자, 1인1표)        ← PostLike 패턴
   ├─ DB upsert: Vote(unique room+member) — 기존 표 있으면 candidate 변경
   └─ 실패 시 Redis compensate
   ▼  afterCommit (TransactionSynchronization)
[SimpMessagingTemplate] /topic/vote-rooms/{publicId} ← 새 집계 broadcast
   ▼
[STOMP] 구독 중인 모든 참가자 화면의 막대그래프가 즉시 갱신

[마감] POST /api/vote-rooms/{publicId}/close   (host만, 멱등)
   → status=CLOSED, winner 확정 (동점 규칙)
   → afterCommit: /topic 에 {type:CLOSED, winner} broadcast → 결과 화면 전환
```

---

## 4. 도메인 모델

새 패키지 `com.ssafy.eatBusan.voteroom` (새 파일 우선 — 머지 충돌 회피, 방법론 §1.3).
`BaseEntity`(soft delete `deleted`) 상속 컨벤션 유지.

### 4.1 엔티티

| 엔티티 | 필드 | 제약/비고 |
|--------|------|----------|
| **VoteRoom** | `id`(PK), `publicId`(예: `VR_xxxx`, 외부 노출용), `title`, `hostMemberId`, `status`(`OPEN`/`CLOSED`), `winnerCandidateId`(nullable), `seedLat`, `seedLng`, `seedRadius`, `createdAt` | PK 직접 노출 금지 → API 경로엔 `publicId` |
| **VoteParticipant** | `id`, `roomId`, `memberId`, `status`(`INVITED`/`JOINED`) | **unique(roomId, memberId)**. 구독·투표 인가의 근거 |
| **VoteCandidate** | `id`, `roomId`, `placeId`(기존 Place 참조), `placeName`(스냅샷), `addedBy`(=host) | 방 생성 시 위치 기반 시드 |
| **Vote** | `id`, `roomId`, `candidateId`, `memberId`, `createdAt`, `updatedAt` | **unique(roomId, memberId)** = 1인 1표. candidate 변경 = update |

### 4.2 상태 머신

```
VoteRoom:  OPEN ──(host close)──► CLOSED(winner 확정)
           - OPEN 상태에서만 투표 허용
           - close는 멱등: 이미 CLOSED면 200 + 기존 winner 반환 (재계산·재push 안 함)

VoteParticipant: INVITED ──(방 입장/구독)──► JOINED
```

### 4.3 인가 규칙 (불변식)
- 투표·구독: `VoteParticipant`에 (room, me)가 있어야 함. 없으면 `403`.
- 마감·후보관리: `hostMemberId == me` 여야 함. 아니면 `403`.
- CLOSED 방 투표 시도: `409`.

---

## 5. Redis 설계 (PostLike 패턴 재사용)

### 5.1 키 구조
| 키 | 타입 | 용도 |
|----|------|------|
| `voteroom:{publicId}:tally` | **ZSET** (member=candidateId, score=표수) | 실시간 집계. `ZINCRBY` 원자 증감, `ZREVRANGE WITHSCORES`로 순위·집계 |
| `voteroom:{publicId}:choice:{memberId}` | String (값=candidateId) | 그 사람의 현재 선택. 1인1표 enforce + 표 변경 처리 |
| `voteroom:{publicId}:bootstrap` | String (flag) | DB→Redis 1회 로드 보장 (like의 `ensureBootstrap`과 동일) |

### 5.2 투표 Lua 흐름 (원자)
```
입력: candidateId(new), memberId
1. prev = GET choice:{memberId}
2. prev 존재 && prev != new  → ZINCRBY tally prev -1
3. prev != new               → ZINCRBY tally new +1 ; SET choice = new
4. prev == new               → 변화 없음 (멱등; 같은 후보 재클릭)
반환: 변경된 후보들의 현재 집계 (또는 전체 tally)
```
> PostLike의 toggle Lua를 "토글" 대신 "이전 제거 + 신규 추가"로 변형. bootstrap/compensate 로직은 거의 그대로.

### 5.3 정합성 규칙 (방법론 §4.3)
- **Redis 먼저 바꾸고 → DB sync.** DB 실패 시 **Redis compensate**(되돌림) 후 예외 재던짐. (절대 예외 삼키고 성공 응답 금지 — PostLike 주석 그대로)
- **Redis 다운 시 DB fallback** 경로 유지(`countByRoomAndCandidate` 류로 집계). like의 `fallbackToDb`와 동형.

---

## 6. REST API 계약

> Base: `/api/vote-rooms`. 인증: 기존 JWT(`@LoginMember MemberDto`). 에러: `EBException` + `ErrorCode`(신규 코드 추가 필요).

| Method | Path | 설명 | 권한 |
|--------|------|------|------|
| `POST` | `/api/vote-rooms` | 방 생성(+위치 기반 후보 시드 +초대) | 로그인 |
| `GET`  | `/api/vote-rooms/{publicId}` | 방 상세(후보·참가자·내 표·상태) | 참가자 |
| `GET`  | `/api/vote-rooms/{publicId}/result` | **현재 집계 스냅샷** (구독 직후/재연결 초기화용) | 참가자 |
| `POST` | `/api/vote-rooms/{publicId}/votes` | 투표/표 변경 | 참가자 |
| `POST` | `/api/vote-rooms/{publicId}/close` | 마감(승자 확정, 멱등) | 호스트 |

### 6.1 요청/응답 예시

**방 생성**
```jsonc
// POST /api/vote-rooms
{ "title": "오늘 점심", "lat": 35.2322, "lng": 129.0838, "radius": 1000,
  "invitedMemberIds": [12, 34, 56] }
// 201
{ "roomPublicId": "VR_a1b2c3",
  "candidates": [ { "candidateId": 1, "placeId": 901, "placeName": "..." } ],
  "participants": [ { "memberId": 12, "status": "INVITED" } ] }
```

**투표**
```jsonc
// POST /api/vote-rooms/VR_a1b2c3/votes
{ "candidateId": 1 }
// 200  (현재 상태 응답 — 토글처럼 200 일괄)
{ "myCandidateId": 1,
  "tally": [ { "candidateId": 1, "count": 3 }, { "candidateId": 2, "count": 1 } ] }
```

**결과 스냅샷 / 마감**
```jsonc
// GET /api/vote-rooms/VR_a1b2c3/result → 200
{ "status": "OPEN", "tally": [ ... ], "winnerCandidateId": null }
// POST /api/vote-rooms/VR_a1b2c3/close → 200
{ "status": "CLOSED", "winnerCandidateId": 1, "tally": [ ... ] }
```

### 6.2 신규 ErrorCode (예시)
`VOTE_ROOM_NOT_FOUND` / `VOTE_ROOM_CLOSED` / `NOT_ROOM_PARTICIPANT` / `NOT_ROOM_HOST` / `CANDIDATE_NOT_IN_ROOM`.

---

## 7. WebSocket / STOMP 설계

### 7.1 의존성
- `build.gradle`: `implementation 'org.springframework.boot:spring-boot-starter-websocket'` 추가.

### 7.2 구성
```
WebSocketConfig (implements WebSocketMessageBrokerConfigurer)
 ├─ registerStompEndpoints: addEndpoint("/ws-stomp")   // (필요시 .withSockJS())
 ├─ configureMessageBroker:
 │     enableSimpleBroker("/topic")    // 인메모리 브로커 (단일 인스턴스)
 │     setApplicationDestinationPrefixes("/app")  // (쓰기를 STOMP로 받지 않으면 사실상 미사용)
 └─ configureClientInboundChannel: interceptors(StompAuthChannelInterceptor)
```

### 7.3 인증·인가 (★최난관, ChannelInterceptor)
```
StompAuthChannelInterceptor implements ChannelInterceptor
 preSend(message):
   StompHeaderAccessor accessor = wrap(message)
   ├─ CONNECT  : Authorization 헤더에서 JWT 추출 → JWTUtil.validate (기존 재사용)
   │             → accessor.setUser(Principal(memberId))   // 이후 프레임에서 사용
   └─ SUBSCRIBE: destination "/topic/vote-rooms/{publicId}" 파싱
                 → Principal(memberId)이 그 방 VoteParticipant인가? 아니면 차단(예외)
```
> 핵심: HTTP의 `JwtFilter`는 WebSocket을 안 탄다. **CONNECT 프레임에서 직접 JWT를 까서 Principal을 심고**, SUBSCRIBE에서 방별 인가를 한 번 더 한다.
> 토큰 전달: 프론트 STOMP `connectHeaders: { Authorization: 'Bearer <token>' }`.

### 7.4 Topic & 메시지 스키마
- 구독: `/topic/vote-rooms/{publicId}`
- 서버 push 페이로드:
```jsonc
// 투표 갱신
{ "type": "TALLY_UPDATED",
  "tally": [ { "candidateId": 1, "count": 3 }, { "candidateId": 2, "count": 1 } ] }
// 마감
{ "type": "ROOM_CLOSED", "winnerCandidateId": 1, "tally": [ ... ] }
```

### 7.5 broadcast 타이밍 (정합성)
- **반드시 트랜잭션 커밋 후에만** push. `TransactionSynchronizationManager.registerSynchronization(... afterCommit())` 안에서 `SimpMessagingTemplate.convertAndSend(...)`.
- 커밋 전 push 금지 — DB 롤백 시 화면이 거짓 집계를 보게 됨.

---

## 8. 어려운 부분 7가지 + 해결책 (체크리스트)

- [ ] **(1) WebSocket 인증** → CONNECT 프레임에서 JWT 파싱하는 `ChannelInterceptor` + 기존 `JWTUtil` 재사용.
- [ ] **(2) 방별 인가** → SUBSCRIBE 프레임에서 destination의 publicId로 `VoteParticipant` 검사.
- [ ] **(3) 1인1표 + 동시성** → Redis Lua 원자 처리 + DB `unique(roomId, memberId)`.
- [ ] **(4) Redis↔DB 정합성** → Redis 먼저 → DB sync → 실패 시 compensate(되돌림) → 예외 재던짐.
- [ ] **(5) 늦은 입장/재연결** → 구독 직후 `GET /result`로 스냅샷 초기화, 이후 topic 델타.
- [ ] **(6) 마감/동점** → close 멱등, 동점 규칙(D2) 명시, CLOSED 후 투표 `409`.
- [ ] **(7) 다중 인스턴스** → 지금은 인메모리 브로커로 충분(단일 인스턴스). 확장 시 Redis pub/sub 릴레이로 교체. **1차 범위 외(단순함 §1.1④).**

---

## 9. 프론트엔드 설계 (Vue3 + TS + Vuetify)

- **STOMP 클라이언트**: `@stomp/stompjs` (필요시 `sockjs-client`).
- **상태**: Pinia `voteRoomStore` — room/candidates/tally/myChoice/status reactive.
- **연결 캡슐화**: composable `useVoteRoom(publicId)`
  - `onMounted`: STOMP connect(`connectHeaders` JWT) → subscribe `/topic/vote-rooms/{publicId}` → `GET /result`로 초기 스냅샷
  - 메시지 수신: `TALLY_UPDATED`→tally 갱신, `ROOM_CLOSED`→결과 화면 전환
  - `vote(candidateId)`: `POST /votes` (push가 화면 갱신을 책임지므로 응답은 내 표 확인용)
  - `onUnmounted`: disconnect / 재연결 핸들링
- **UI**: Vuetify `v-progress-linear` 막대그래프 실시간 상승, 호스트에게만 "투표 마감" 버튼, CLOSED 시 winner 하이라이트.
- **문서 주도**: 위 §6 API + §7.4 메시지 스키마를 `docs/frontend/API_CONTRACT.md`에도 반영 후 프론트 착수.

---

## 10. 구현 로드맵 (이슈 단위)

> 전략: **이슈A에서 WebSocket 없이 폴링으로 완전 동작하는 투표를 먼저 완성** → 가장 어려운 WebSocket을 "이미 되는 기능 개선"으로 격하.

| 이슈 | 내용 | 완료 기준 |
|------|------|-----------|
| **A. 도메인 + REST (WS 0)** | VoteRoom CRUD, 위치 기반 후보 시드(PlaceService 재사용), 초대, 투표 POST + Redis 집계(PostLike 복제), `GET /result` | 폴링으로 다인 투표 동작, DB·Redis 정합 검증 |
| **B. WebSocket 레이어** | `starter-websocket`, `WebSocketConfig`, `StompAuthChannelInterceptor`(인증+인가), afterCommit broadcast | 두 클라이언트가 실시간 동시 갱신 확인 |
| **C. 프론트 실시간** | `@stomp/stompjs` + `useVoteRoom` + Pinia + Vuetify 막대 | 브라우저 2개로 실시간 반영 시각 확인 |
| **D. 마감/승자** | 호스트 close(멱등), 동점 규칙(D2), 결과 화면 | 마감 시 전원 결과 화면 전환 |

---

## 11. 검증 전략 (방법론 §4 — 응답 + DB 이중검증)

### 11.1 REST E2E (이슈 A·D)
- 실서버 기동(빈 포트 탐색) → curl로 케이스별 요청 → **HTTP 응답 + DB 상태** 둘 다 확인.
- 상태변경 API(투표/마감)는 **BEFORE/AFTER 동일 쿼리 스냅샷**:

| 동작 | 검증식 |
|------|--------|
| 첫 투표 | `tally(after) == tally(before) + 1`, `Vote` row 1개 생성 |
| 표 변경(A→B) | `A == before-1`, `B == before+1`, `Vote` row **update**(insert 아님), 총 표수 불변 |
| 같은 후보 재클릭 | 모든 집계 불변 (멱등) |
| 비참가자 투표 | `403`, 집계 불변 |
| CLOSED 방 투표 | `409`, 집계 불변 |
| 마감 | `status: OPEN→CLOSED`, `winner` 확정, 재호출 시 멱등(불변) |

> **불변 검증 필수**: "안 바뀌어야 할 후보 집계/총 표수"가 그대로인지 반드시 대조(부작용 검증).

### 11.2 케이스 분류 (각 엔드포인트)
정상 / 유효성(필수 누락·잘못된 candidateId) / 비즈니스(중복·CLOSED) / 권한(비참가자·비호스트) / 리소스없음(없는 publicId) / 동시성(두 명 동시 투표).

### 11.3 WebSocket 검증 (이슈 B·C)
- 단순 STOMP 테스트 클라이언트 2개 연결 → 한쪽 투표 → 다른 쪽 topic 수신 확인.
- 프론트는 **브라우저 2개**로 실제 막대 실시간 상승을 **시각 확인**(스크린샷 증거).
- 인증 실패(토큰 없음) CONNECT 거부, 비참가자 SUBSCRIBE 거부 확인.

---

## 12. 참고: 재사용할 기존 자산

| 새 기능 요소 | 재사용 대상 |
|--------------|-------------|
| 위치 기반 후보 시드 | `place/Service/PlaceService.searchPlace(PlaceRequestDto)` + `RestClientConfig.kakaoClient` |
| Redis 원자 집계 + DB sync + compensate | `postlike/service/PostLikeService`, `PostLikeCacheService` (Lua/bootstrap/fallback 패턴) |
| 인증 주체 주입 | `auth/resolver/@LoginMember` + `MemberDto` |
| WebSocket JWT 검증 | `auth/util/JWTUtil` |
| 예외/에러코드 | `global/exception/{EBException, ErrorCode, GlobalExceptionHandler}` |
| soft delete 베이스 | `global/entity/BaseEntity` |

---

## 13. 범위 밖 (이번엔 안 함)
- 다중 서버 인스턴스 STOMP 릴레이(Redis pub/sub) — 단일 인스턴스로 시작.
- deadline 자동 마감 스케줄러 — 호스트 수동 마감만.
- 초대 코드/링크, 친구 검색 — memberId 직접 지정으로 시작(D3).
- 참가자가 후보 추가 — 호스트 위치 시드만(필요 시 후속).

---

## 14. 확정된 미해결 결정 (2026-06-11, 구현 착수 시점)

| # | 결정 | 근거 |
|---|------|------|
| **D1. 후보 개수** | `searchPlace()` 결과 **앞 5개** 그대로 사용 (거리순 무보장) | searchPlace는 Kakao distance를 버리고 DB 순서로 반환 → 거리순 정렬 불가. 기존 메서드 재사용 우선 |
| **D2. 동점 규칙** | 1차: **최소 candidateId 승리** (완전 결정론). 동점 재투표(RUNOFF)는 이슈 E로 후속 확장 | "먼저 도달" 규칙은 도달 시각 이력이 없어 현재 모델로 계산 불가. 재투표를 넣어도 최종 동점용 결정론 규칙은 어차피 필요 |
| **D3. 초대 방식** | **memberId 리스트** 직접 지정 (문서 제안대로) | |

### 구현 시 주의 (코드 검증으로 발견된 사실)
- `JWTUtil.validateToken/getId`는 ACCESS 타입일 때 내부에서 `substring(7)` → **"Bearer " 접두사를 포함한 원문**을 넘겨야 함.
- `PlaceRequestDto(x, y, radius)`에서 **x=경도(lng), y=위도(lat)** — API의 `{lat, lng}`와 순서 뒤집어 매핑.
- ZSET tally는 ZINCRBY 안 된 후보를 갖지 않음 → **bootstrap 시 모든 candidate를 score 0으로 ZADD** 해야 0표 후보가 집계에 나타남.
- `JwtFilter` WHITE_LIST에 `/api/**`(개발용)가 있어 모든 API가 필터 우회 중 → 토큰 없는 요청은 `@LoginMember`에서 NPE(500). 1차 범위에서는 그대로 두되 인지할 것.
