# 리더 진입 시 시작 위치 결정 규칙

책을 열 때 "몇 페이지부터 보여줄까" 를 정하는 현재 로직 전체. 코드 출처를 줄 단위로
명시했으니 md 만 보고 규칙을 수정/튜닝 판단 가능.

두 레벨의 결정이 있음:
- **앱 런치 결정** (콜드 스타트 직후) — 리스트 / 리더 자동 진입 / 팝업 중 하나
- **책 오픈 결정** (책 클릭 후) — KEEP / JUMP / ASK 중 하나

## 앱 런치 결정 (콜드 스타트)

콜드 스타트 시 리스트 화면이 기본으로 뜨고, 그 위에서 한 번만 실행되는 결정.
회전 등 config change 복원 시엔 **스킵** (`savedInstanceState != null` 가드).

**입력**:
- `localMostRecent` = `LocalProgressRepository.getMostRecent()` — lastRead 최대 entry
- `nasLatestOther` = NAS `pos_*.json` 중 **내 deviceId 가 아닌** 것 중 lastUpdatedEpoch 최대
- `lastExitMode` = 마지막 종료 시점 탭 (`AppSessionPrefs.MODE_READER` / `MODE_LIST`)

**결정표**:

| 조건 | 동작 |
|---|---|
| NAS 꺼짐/미연결 → fallthrough | Local-only 결정표로 |
| `nasLatestOther.epoch > localMostRecent.epoch` | **팝업** "다른 단말에서 [책] X%까지 읽었습니다. 이어보시겠습니까?" 예/아니오 |
| Local-only: local 없음 | 리스트 유지 |
| Local-only: `lastExitMode == list` | 리스트 유지 (사용자가 의도적으로 리더에서 나왔음) |
| Local-only: `lastExitMode == reader` | 해당 책 **자동 리더 진입** (`skipConflict=true`) |
| 콜백 도착 시 사용자가 이미 다른 탭으로 이동 | 동작 안 함 (방해 안 함) |
| NAS 팝업 "아니오" | 리스트 유지 |
| NAS 팝업 "예" + 로컬에 파일 있음 | 리더 진입, `charOffset=NAS offset`, `skipConflict=true` |
| NAS 팝업 "예" + 로컬에 파일 없음 | 토스트 "이 기기에 파일이 없습니다", 리스트 유지 |

**`lastExitMode` 기록 시점**: `ViewPager2.OnPageChangeCallback.onPageSelected` 에서 매번.
position 2 → `reader`, 그 외 → `list`. 즉 사용자가 마지막으로 머물던 탭이 프로세스 킬
까지 유지됨.

**코드 경로**: `BookListActivity.runLaunchDecision` (`onCreate` 안 `savedInstanceState == null` 분기).

**주의**:
- 이 결정은 책 오픈 결정 (ConflictResolver) 을 **대체하지 않음**. 팝업에서 "아니오" 를
  눌러도 나중에 그 책을 리스트에서 탭하면 ConflictResolver 가 다시 돌아 ASK 다이얼로그를
  띄울 수 있음 (보완 관계).
- 팝업은 "가장 최근 NAS 기록 1건" 만 비교. 여러 책이 local 보다 최신이어도 한 번에
  한 책만 묻는 단순 스펙.

---

## 책 오픈 결정 (리더 진입 후)

책 오픈 시 **로컬 진행 기록** (LocalProgressRepository) 과 **NAS 진행 기록** (pos_*.json)
을 비교해 어디로 점프할지 결정. 세 결과 중 하나:

- **KEEP(offset)** — 지정 offset 유지. 다이얼로그 없음.
- **JUMP(offset)** — 지정 offset 으로 이동. 다이얼로그 없음.
- **ASK(offset)** — "이어 읽기" 다이얼로그 표시. 사용자가 결정.

## 전체 흐름

```
[사용자 책 선택]
    ↓
BookListFragment / MyBookmarksFragment / NasHistoryFragment
    → host.openBook(path, startOffset, skipConflict)
    ↓
ReaderFragment.onResume → loadCurrentBookFromHost()
    ↓
[효과적 startOffset 결정]
    • progressRepo.get(fileHash) 가 있으면     → entry.charOffset    (회전 복원 경로 등)
    • 없으면                                    → host.startOffset     (초기 진입 경로)
    ↓
loadFile(file, effectiveStartOffset)
    ↓
paginate(effectiveStartOffset)
    → 페이지네이션 캐시/계산 완료
    → displayPage(pageRenderer.offsetToPage(effectiveStartOffset))
    ↓
maybeResolveNasConflict()        ← 여기서 NAS 비교 + 점프 결정
    • skipConflictResolve=true 면 **스킵**
    • nas.isEnabled() / isConnected() false 면 **스킵**
    • conflictResolved=true 이미 해결했으면 **스킵**
    • 그 외 → nasSyncManager.fetchOne(fileHash) 후 콜백에서
      ConflictResolver.resolve(...) → applyConflictOutcome(...)
```

## 각 호출자의 `skipConflict` 기본값

| 진입 경로 | skipConflict | 의미 |
|---|---|---|
| **내 책 탭에서 책 클릭** (`BookListFragment.openBook`) | `false` | 로컬 vs NAS 비교 수행 |
| **즐겨찾기 → 내 북마크 클릭** (`MyBookmarksFragment.openBookmark`) | `true` | 이미 특정 북마크 offset 으로 진입, 비교 불필요 |
| **즐겨찾기 → 다른 단말 진행 클릭** (`NasHistoryFragment.openBook`) | `true` | 이미 NAS offset 으로 진입, 재비교 무의미 |
| **회전 후 복원** (`BookListActivity.markConflictResolvedForCurrentBook` 가 true 로 세팅) | `true` | 이미 한 번 resolve 됐으므로 재프롬프트 금지 |

## ConflictResolver.resolve() 의사 결정

**파일**: `app/src/main/java/com/example/minseo3/nas/ConflictResolver.java`

입력:
- `localOffset` : 로컬이 가진 charOffset (LocalProgressRepository.Entry.charOffset)
- `localLastReadEpoch` : 로컬이 마지막 저장된 시각 (ms). 없으면 **0**.
- `localDeviceId` : 내 기기 ID (Settings.Secure.ANDROID_ID 앞 16자 또는 UUID 폴백)
- `nas` : NAS pos_*.json 내용 (RemotePosition). NAS 에 기록 없으면 **null**.

출력: ConflictOutcome (keep / jump / ask)

### 결정 순서 (위에서 아래, 먼저 걸리는 규칙이 이김)

```
1. nas == null
   → KEEP(localOffset)                [NAS 에 이 책 기록 없음 — 로컬 유지]

2. localLastReadEpoch == 0
   → JUMP(nas.charOffset)              [로컬 기록 전혀 없음 — NAS 따라감]
                                        (재설치 / 새 기기 / 처음 읽기 직후)

3. 둘 다 있음 → 비교:
      timeDiff   = |nas.lastUpdatedEpoch - localLastReadEpoch|
      offsetDiff = |nas.charOffset - localOffset|
      sameDevice = (localDeviceId == nas.deviceId)

   3a. timeDiff < 5 min AND offsetDiff < 500 chars
       → KEEP(localOffset)              [같은 세션으로 간주 — 자기 자신 NAS push
                                         로컬이 받아쓰기 않게]

   3b. nas.lastUpdatedEpoch > localLastReadEpoch (NAS 가 더 최신):
       3b-i.  다른 기기 AND offsetDiff >= 500 AND timeDiff < 5 min
              → ASK(localOffset)        [최근에 다른 기기에서 대폭 진행 —
                                         사용자에게 이어 갈지 물어봄]
       3b-ii. 그 외 (같은 기기거나 offset 차이 작거나 5분 넘음)
              → JUMP(nas.charOffset)    [NAS 가 최신 — 따라감]

   3c. localLastReadEpoch >= nas.lastUpdatedEpoch (로컬이 더 최신이거나 동일):
       → KEEP(localOffset)              [로컬이 최신 — NAS 는 무시하고 유지]
```

### 상수

- `SAME_SESSION_WINDOW_MS = 5 * 60 * 1000` (5 분) — `NasSyncConstants.java:7`
- `SAME_SESSION_OFFSET_DIFF = 500` — 한국어 분당 ~300-500자 가정, `NasSyncConstants.java:10`

### ConflictOutcome 세 종류

**파일**: `nas/ConflictOutcome.java`

- `keep(offset)` : `{resolvedOffset: offset, needsDialog: false}`
- `jump(offset)` : `{resolvedOffset: offset, needsDialog: false}`
- `ask(localOffset)` : `{resolvedOffset: localOffset, needsDialog: true}`

**내부 구조**: keep 과 jump 는 needsDialog=false 로 동일하게 처리됨 (조용히 이동).
차이는 **의도** — keep 은 "움직이지 않음", jump 은 "NAS 를 따라 이동".
`ReaderFragment.applyConflictOutcome` 에서 `resolvedOffset != currentOffset` 이면
`displayPage(pageRenderer.offsetToPage(resolvedOffset))` 호출.

## 읽는 동안: 기기 간 진행 동기화 (last-writer-wins)

> 여기는 "열 때" 가 아니라 "읽는 도중" 규칙. 사용성 측면 검토 대상.

### 기본 동작

같은 파일 (파일명 + 크기 동일 → `fileHash` 일치) 은 NAS 상 단 하나의
슬롯 `pos_{fileHash}.json` 을 공유. 읽는 단말이 자신의 offset + deviceId +
시각으로 덮어씀 → **마지막 쓴 기기가 이김**.

### 호출 체인 (debounced)

local save 와 NAS push 는 **같은 `saveRunnable` 에 묶여 있어 5초 debounce 를 공유**.
페이지 이동마다 `scheduleSave()` 가 기존 예약을 취소하고 5초 뒤로 다시 예약 →
연타하면 마지막 위치만 반영됨.

```
ReaderFragment (페이지 넘김)
    → scheduleSave() → saveHandler.postDelayed(saveRunnable, 5000)
    ↓ (5초 내 추가 이동 없으면 fire)
saveRunnable
    → progressRepo.save(...)           [로컬 reading_progress.json]
    → nasSyncManager.push(...)         [NAS push]
        → networkExecutor 에서
        → SynologyFileStationRepository.push(fileHash, RemotePosition, cb)
        → DSM FileStation upload /webapi/entry.cgi
        → pos_{fileHash}.json 덮어쓰기 (이전 deviceId 의 기록 소실)
```

즉시 flush 경로:
- `flushSaveNow()` — onPause / conflict resolve 수락 시 5초 기다리지 않고 즉시 push.
- Reader 가닫히거나 백그라운드로 가면 항상 flush.

코드 경로:
- `ReaderFragment.java:103-108` — saveRunnable 정의 (local save + NAS push 한 덩어리)
- `ReaderFragment.java:478-481` — scheduleSave (5s debounce)
- `ReaderFragment.java:483-491` — flushSaveNow (즉시)
- `NasSyncManager.push()` — dispatch, 로그 `SACH_NAS push pos dispatch`
- `SynologyFileStationRepository.push()` — 실제 HTTP upload

### 시나리오 예시

```
[T+0]   탭에서 80% 읽고 닫음
        → NAS pos_{hash}.json = {charOffset=412000, deviceId=탭, epoch=T+0}

[T+5m]  폴드에서 같은 파일 열기
        → ConflictResolver.resolve(localOffset=0, localEpoch=0, nas=탭기록)
        → 규칙 2번 발동 (localLastReadEpoch==0) → JUMP(412000)
        → 폴드가 80% 지점부터 바로 진입 (다이얼로그 없음)

[T+6m]  폴드에서 85% 까지 계속 읽음
        → 페이지 이동 멈추고 5초 경과 시점마다 push (debounced)
        → NAS pos_{hash}.json = {charOffset=..., deviceId=폴드, epoch=최신}
        → 탭의 기록은 덮어써져 사라짐
        → onPause (앱 전환/닫기) 에서는 flushSaveNow 로 즉시 push

[T+1h]  탭에서 다시 열기
        → ConflictResolver 비교 → 규칙 3b-ii → JUMP(85%)
        → 탭이 폴드 위치로 이어감
```

### 설계 의도

- **장점**: 세팅 없이 "어느 기기에서든 이어 읽기" 가 됨. 동기화 타이밍 고민 불필요.
- **단점**: NAS 는 항상 1개 슬롯. 과거 deviceId 의 offset 은 복원 불가.
  "탭에서 어디까지 봤더라" 같은 기기별 히스토리 조회는 불가능.

### 사용성 검토 포인트 (나중에 논의)

- **두 기기가 동시에 열어서 각자 읽을 때**: 각자 5초 debounce 로 push 하므로 마지막 쓴 쪽이 이김. 끄고 다시 열면 다른 기기 offset 으로 끌려갈 수 있음.
  - 완화: 5분 · 500자 이내면 KEEP (규칙 3a) — 세션 내 "내 자신 push 를 받아쓰지 않게" 만 방어. 타 기기 concurrent write 는 방어 안 함.
- **읽기만 하고 닫는 경우**: 페이지 이동이 없으면 push 안 됨 → NAS 는 이전 값 유지. 의도적.
- **5초 안에 앱 죽거나 프로세스 킬**: scheduleSave 가 fire 전이면 그 구간 push 소실 가능. onPause 의 flushSaveNow 가 대부분 커버하지만 크래시 시 안 탐.
- **NAS offline 중 읽기**: push 실패 → `connected=false` 로 전환, 이후 성공 시까지 NAS 는 stale. 로컬 진행만 계속 기록. 재연결 시 첫 성공 push 에서 따라잡음.
- **기기별 히스토리가 필요하면?** 현재 스키마 변경 필요 (`pos_{hash}_{deviceId}.json` 배열 형태) — NAS 파일 수 N배, fetchAll 비용 증가. 해볼지는 실제 불편을 느낄 때.
- "다른 단말 진행" 탭은 **마지막에 write 한 타 단말의 기록** 만 보여줌. 같은 책을 3기기가 돌려 읽으면 가장 최근 1건만 남음 (스키마 한계).

### 관련 임계값

- 페이지 이동 save/push debounce: **5초** (`ReaderFragment.scheduleSave` → `saveHandler.postDelayed(..., 5000)`). local save 와 NAS push 가 같은 runnable.
- Bookmark push debounce: 1초 (`NasSyncManager.BOOKMARK_PUSH_DEBOUNCE_MS`).

---

## 최근 문제 사례 (2026-04-24)

### 사례: 폴드 재설치 후 책 열기 → 80% 부터 시작

**증상**: 폴드에서 앱 uninstall 후 재설치. "애드립의신_5" 열었더니 80% 지점으로 바로 진입.

**원인**: 재설치 → 로컬 LocalProgressRepository 빈 상태 → `localLastReadEpoch = 0`.
규칙 **2번** (로컬 기록 없음) 발동 → `JUMP(nas.charOffset)`. NAS 에 이전 세션 (탭 또는
폴드 과거 설치) 에서 80% 지점 기록이 있었기 때문.

**다이얼로그 없이 점프하는 이유**: 의도된 설계. "재설치 = 이어 읽기" 가정.

**대안** (변경 요청 시 후보):
- 옵션 A (현재 유지): 조용히 점프 — 재설치 시 이어 읽기 편의.
- 옵션 B: 규칙 **2번** 을 `ASK` 로 바꿔 다이얼로그 표시 — 사용자가 "0 부터 / NAS 위치로" 선택.
- 옵션 C: 규칙 **2번** 을 `KEEP(0)` 으로 — NAS 무시, 새로 시작. (NAS 진행 동기화 의미 상실)

## 관련 파일

| 파일 | 역할 |
|---|---|
| `nas/ConflictResolver.java` | 핵심 분기 로직 |
| `nas/ConflictOutcome.java` | 결과 타입 |
| `nas/NasSyncConstants.java` | 임계값 상수 |
| `nas/RemotePosition.java` | NAS pos_*.json 구조 |
| `LocalProgressRepository.java` | 로컬 진행 `reading_progress.json` |
| `ReaderFragment.java` | `maybeResolveNasConflict`, `applyConflictOutcome`, `showConflictDialog` |
| `BookListActivity.java` | `currentBookSkipConflict` 상태, `markConflictResolvedForCurrentBook` |

## 엣지 케이스 참고

1. **NAS 미연결 / 비활성**: `maybeResolveNasConflict` 가 `nas.isEnabled() || isConnected() == false` 로 스킵. 로컬 offset 유지.
2. **파일이 로컬에 없음** (다른 단말 진행에서 해당): Fragment 가 토스트 "이 기기에 파일 없음" 표시, 리더 진입 자체를 막음.
3. **NAS fetch 실패**: `onError` 콜백 — `applyConflictOutcome` 호출 안 됨, 로컬 유지.
4. **첫 페이지 즉시 렌더링 중 paginate 완료 전**: `paginationReady=false` 동안 `maybeResolveNasConflict` 는 호출 안 됨 (paginate 완료 `mainHandler.post` 안에서만).
5. **회전 중 재진입**: `BookListActivity.currentBookSkipConflict=true` 승격 덕에 중복 prompt 없음.
