# 리더 진입 시 시작 위치 결정 규칙

책을 열 때 "몇 페이지부터 보여줄까" 를 정하는 현재 로직 전체. 코드 출처를 줄 단위로
명시했으니 md 만 보고 규칙을 수정/튜닝 판단 가능.

## 개요

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
