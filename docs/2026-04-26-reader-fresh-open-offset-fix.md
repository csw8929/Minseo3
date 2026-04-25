# Reader fresh-open offset 버그 — 진단/수정 기록 (2026-04-26)

NAS 팝업 "다른 단말에서 25% 까지 읽었습니다" → "예" 선택 시 0% 부터 시작되는
버그. 한 줄짜리 로그로 가설 확정 후 fix. 같은 패턴의 멀티 분기 디버깅에서
재현/응용할 수 있도록 로그 박는 사고 과정까지 기록.

## TL;DR

| 항목 | 내용 |
|---|---|
| 증상 | 탭 콜드 스타트 → 팝업 "25%" → "예" → 리더가 0% 에서 시작 |
| Root cause | `ReaderFragment.loadCurrentBookFromHost` 가 **회전 복원 케이스** 때문에 무조건 `progressRepo` (local) 를 우선. 결과적으로 popup/list/bookmark/NasHistory 가 명시적으로 넘긴 `startOffset` 이 stale local entry (charOffset=0) 에 덮임 |
| Fix | `BookListActivity` 에 `currentBookFreshOpen` 플래그 추가. `openBook()` 호출 시 true, `onSaveInstanceState` 에 저장 안 함 → 회전 시 false 로 자연 초기화. ReaderFragment 가 fresh open 이면 host startOffset 신뢰 |
| 변경 파일 | `BookListActivity.java`, `ReaderFragment.java` |

## 증상

```
폴드: 책 25% 까지 읽음 → 닫음 (NAS push)
탭: 콜드 스타트
탭: 팝업 "다른 단말에서 25% 까지 읽었습니다. 이어 보시겠습니까?"
탭: "예" 누름
탭: 리더 진입 → 0 페이지 (책 처음) 부터 시작 ❌ 25% 지점에서 시작했어야 함
```

## 잘못된 초기 가설

이 세션 이전 (2026-04-25 무렵) 에 있었던 비슷한 cross-device 동기화 미세
어긋남 (1~2 페이지) 을 layout-rounding 으로 추정했었음. 그 가설은:

> 저장값은 char offset (단말 무관) 이지만 "어느 페이지" 인지는 수신 단말의
> layout 으로 paginate 된 `pageOffsets[]` 에 의존 → snap-down 으로 1~2 페이지
> 어긋남.

**기각**: 실제 증상은 "1~2 페이지" 가 아니라 "0% (책 처음) 부터 시작" 이었고,
방향도 snap-down (앞쪽) 이 아니라 완전히 별개 위치. 동일 메커니즘으로 설명 불가.

## 코드 추적

`ReaderFragment.java:217-218` 에 의심 로직 발견:

```java
LocalProgressRepository.Entry saved = progressRepo.get(fileHash);
int effectiveStartOffset = (saved != null) ? saved.charOffset : startOffset;
```

주석 (line 214-216):
> rotation 복원 경로: onPause 에서 flushSaveNow 로 progressRepo 에 최신 offset
> 이 이미 저장됨. host.currentBookStartOffset 은 "최초 오픈 시점" 값이라
> 읽던 페이지와 다를 수 있으므로 progressRepo 우선.

회전 복원 케이스를 위해 짠 로직이지만, **회전과 fresh open 을 구분하지
않음** → popup/list/bookmark 가 넘긴 explicit startOffset 도 똑같이 stale
local 에 덮임.

## 가설 검증 — 로그 박기

코드만으로 root cause 가 거의 확정됐지만, Iron Law (root cause 확정 후 fix)
준수를 위해 로그 한 줄로 시그니처 확인. 분기 입력 + 결과 + sanity 한 번에 찍기:

```java
android.util.Log.i("ReaderFragment",
    "loadFromHost: hostStart=" + startOffset
    + " skipConflict=" + skipConflict
    + " savedLocal=" + (saved != null ? saved.charOffset : -1)
    + " savedLastRead=" + (saved != null ? saved.lastRead : -1)
    + " effective=" + effectiveStartOffset
    + " hash=" + fileHash);
```

### 실제 출력

```
04-26 00:46:00.298  9078  9078 I ReaderFragment: loadFromHost:
  hostStart=457176 skipConflict=true savedLocal=0
  savedLastRead=1777131774772 effective=0 hash=730c7cf9c80ab4f4
```

### 필드별 해석

| 필드 | 값 | 의미 |
|---|---|---|
| `hostStart` | 457176 | `host.getCurrentBookStartOffset()` — 호출자가 명시적으로 넣어준 시작 offset. 이 케이스에선 popup → 예 → `openBook(path, nas.charOffset, true)` 의 `nas.charOffset` = 폴드가 NAS push 한 25% 지점 character offset |
| `skipConflict` | true | popup 경로는 이미 사용자가 NAS 위치 선택했으므로 reader 안에서 `maybeResolveNasConflict` 재실행 안 함. **그래서 한 번 잘못 정해진 위치를 되돌릴 안전망이 없음** |
| `savedLocal` | 0 | local entry 의 charOffset. `-1` 이면 entry 없음 (sentinel). `0` 은 entry 가 **존재하되 charOffset 이 0** — 탭이 과거에 책을 잠깐 열어봤지만 거의 안 읽고 닫음 |
| `savedLastRead` | 1777131774772 | local entry 의 `lastRead` (epoch ms) ≈ 2026-04-25 09:13 KST. 어제 오전 탭에서 잠깐 열었던 흔적 |
| `effective` | 0 | `effectiveStartOffset` — fix 전 로직 `(saved != null) ? saved.charOffset : startOffset` 결과. saved 존재 → savedLocal (0) 채택 → **여기가 버그 발현 지점** |
| `hash` | 730c7cf9c80ab4f4 | sha256(파일명+크기) 앞 16hex. 폴드/탭이 같은 파일 (fileHash 충돌/파일 다름 가설 배제) |

### 시그널

- `hostStart=457176` ≠ `effective=0` → **덮어쓰기 발생 확정**
- `savedLastRead=2026-04-25` < NAS push 시각 (오늘) → local 이 stale 임이
  시간 축으로도 확인됨. 단순 "0 이라서" 가 아니라 "오래된 0 이라서" 라는 보강.
- `skipConflict=true` → 보정 경로 막힘 → 한 번 0 으로 정해지면 그대로 진행

이 한 줄로 가설 100% 확정. 추가 로그 / 추가 재현 불필요.

## Fix

### 설계: fresh open vs 회전 복원 구분

| 진입 경로 | host.startOffset | local | 어느 쪽이 신뢰할 만한가 |
|---|---|---|---|
| popup → 예 | NAS offset (방금 set) | stale (과거 세션) | **host** |
| 리스트 클릭 | local.charOffset | 같음 | 둘 다 동일 |
| 회전 복원 | "최초 오픈 시점" 값 (stale) | 회전 직전 flushSaveNow 가 갱신 (신선) | **local** |
| 북마크/NasHistory 클릭 | bookmark/nas offset (방금 set) | stale 가능 | **host** |

→ 구분 신호: **이 Activity 인스턴스 내에서 `openBook()` 가 호출됐는가?**
- 호출됨 = fresh open (어떤 경로든 host 가 방금 set 됨) → host 신뢰
- 호출 안 됨 = 회전 복원 (Activity 가 새로 만들어졌고 `onCreate` 에서 bundle
  복원만 됨) → local 신뢰

이 신호는 `boolean currentBookFreshOpen` 필드로 자연스럽게 표현됨:
- `openBook()` 호출 시 `true` set
- `onSaveInstanceState` 에는 **저장 안 함** → 회전 후 `onCreate` 에서 default
  `false` 로 초기화 (Java primitive boolean 기본값)

### 변경 파일

#### `BookListActivity.java`

필드 추가:
```java
/** openBook() 으로 진입한 직후 true. 회전 후 onCreate 복원 시엔 false 로 초기화
 *  (savedInstanceState 에 저장 안 함) — 이를 통해 ReaderFragment 가 fresh
 *  navigation 인지 회전 복원인지 구분, popup/list/bookmark 의 명시적 startOffset
 *  과 회전 시점의 stale local 을 올바르게 분기. */
private boolean currentBookFreshOpen;
```

getter 추가:
```java
public boolean isCurrentBookFreshOpen() { return currentBookFreshOpen; }
```

`openBook` 에서 set:
```java
public void openBook(String filePath, int startOffset, boolean skipConflict) {
    this.currentBookPath = filePath;
    this.currentBookStartOffset = startOffset;
    this.currentBookSkipConflict = skipConflict;
    this.currentBookFreshOpen = true;            // ← 추가
    applyChromeForPosition(2);
    viewPager.setCurrentItem(2, true);
}
```

`onSaveInstanceState` / `onCreate` 의 bundle 처리에는 **추가 안 함** —
회전 시 false 로 자연 초기화되는 것이 핵심.

#### `ReaderFragment.java`

`loadCurrentBookFromHost` 분기 수정:

```java
LocalProgressRepository.Entry saved = progressRepo.get(fileHash);
boolean freshOpen = host.isCurrentBookFreshOpen();
int effectiveStartOffset;
if (freshOpen) {
    effectiveStartOffset = startOffset;
} else {
    effectiveStartOffset = (saved != null) ? saved.charOffset : startOffset;
}
```

### 정합성 점검 (모든 진입 경로)

| 케이스 | freshOpen | host.startOffset | local | effective | 기대 |
|---|---|---|---|---|---|
| popup → 예 (탭에 과거 흔적 0%) | true | NAS (25%) | 0% (stale) | **25%** | ✓ 25% (버그 fix) |
| 리스트 클릭 (local 30%) | true | 30% | 30% | 30% | ✓ |
| 리스트 클릭 (첫 오픈, local 없음) | true | 0 | null | 0 | ✓ |
| 회전 복원 (30% 까지 읽다가) | false | 25% (최초) | 30% (방금) | **30%** | ✓ |
| 북마크 클릭 | true | bookmark offset | stale 가능 | bookmark | ✓ |
| NasHistory 클릭 | true | NAS offset | stale 가능 | NAS | ✓ |

회전 복원 케이스 (마지막 줄) 는 fix 후에도 정상 동작 — `currentBookFreshOpen`
이 false 로 초기화되어 기존 progressRepo-우선 로직이 그대로 발동.

## 검증

```bash
./gradlew assembleDebug   # ✓ BUILD SUCCESSFUL
./gradlew test            # ✓ BUILD SUCCESSFUL
```

진단 로그는 fix 와 함께 **제거**했음 (production 로그 정결성 유지 — 2026-04-22
NAS 디버깅 기록의 정책과 동일).

자동 회귀 테스트는 미작성. Activity-Fragment 상호작용 + savedInstanceState
사이클이라 JVM 단위 테스트 영역 밖, instrumented test 가 필요한데 현재
프로젝트엔 그 스캐폴딩 없음. 수동 재현 검증으로 대체.

### 수동 검증 절차

1. APK 탭에 설치
2. 시나리오 셋업:
   - 폴드에서 책 1페이지만 읽고 닫음
   - 탭에서 그 책 잠깐 열고 0~5% 에서 닫음 (stale local entry 생성)
   - 폴드에서 25% 까지 읽고 닫음 (NAS 25% push)
3. **탭 콜드 스타트** → 팝업 "다른 단말에서 25% 까지" → "예"
4. **기대**: 25% 지점 페이지에서 시작 (이전엔 0%)
5. 회전 검증:
   - 25% 시작 후 30% 까지 읽음
   - 화면 회전
   - **기대**: 30% 유지 (회전 복원 정상 동작)

## 배운 점

### 1. 분기 버그 진단은 "입력 + 결과" 를 한 번에

분기점 (`if/else`, ternary) 로 인해 동작이 갈리는 버그는 **분기의 입력 + 결과**
를 동시에 찍는 게 핵심. 결과만 찍으면 "왜 그렇게 됐는지" 가설별로 또 분기
로그를 박아야 함. 입력 + 결과 묶음으로 한 번에:

- 입력 두 값 (hostStart, savedLocal)
- 분기 조건의 변수 (skipConflict — 다른 안전망 막혔는지)
- 결과 (effective)
- sanity (hash, savedLastRead)

→ 한 줄로 가설 확정 가능. ConflictResolver 같은 멀티 분기 로직 디버깅에도 동일.

### 2. "회전 복원" vs "fresh open" 은 의외로 자주 헷갈림

`onPause` → `flushSaveNow` → `onSaveInstanceState` → `onCreate(bundle)` →
`onResume` → `loadCurrentBookFromHost` 의 회전 사이클은 일반 fresh open 의
사이클과 호출 시점이 매우 비슷하지만 **상태의 신선도가 정반대**:

- 회전: bundle 의 currentBookStartOffset 은 stale (변경 안 됨), local 은 신선
- fresh open: host 의 currentBookStartOffset 은 신선 (방금 set), local 은 stale 가능

이 비대칭을 코드에서 명시적으로 구분 안 하면 한쪽 케이스 버그가 됨.
**`onSaveInstanceState` 에 저장 안 하는 플래그** 가 회전과 fresh 를 가르는
가장 단순한 신호 (회전 = bundle 만 복원, 플래그는 default 로 reset).

### 3. skipConflict 와 freshOpen 은 직교

이전엔 `skipConflict` 를 "fresh open 인가" 신호로도 겸용 가능한지 잠깐
고려했지만, 회전 복원 시 `markConflictResolvedForCurrentBook` 으로 인해
`skipConflict=true` 가 유지될 수 있음 → fresh 신호로 부적합.

- `skipConflict` = NAS 충돌 해결 스킵 여부 (한 번 resolve 됐으면 skip)
- `freshOpen` = openBook 호출 직후인가 (회전 복원과 구분)

두 개는 별개 개념. docs/2026-04-24-reader-jump-position-rules.md 의 "각
호출자의 skipConflict 기본값" 표는 이 fix 후에도 그대로 유효.

### 4. 한 줄 로그의 가성비

이번처럼 가설이 거의 확정된 상태에선 멀티 라인 로그 / 멀티 단계 instrumentation
보다 **한 줄에 모든 시그널 묶기** 가 압도적으로 효율. 로그 박는 비용 (1 줄
edit + 빌드 + 재현 1회) vs 가설 확정의 가치 (Iron Law 충족 + 회귀 테스트
데이터 확보) 가 매우 비대칭.

## 관련 파일

- `app/src/main/java/com/example/minseo3/BookListActivity.java`
  — `currentBookFreshOpen` 필드, getter, `openBook` set
- `app/src/main/java/com/example/minseo3/ReaderFragment.java`
  — `loadCurrentBookFromHost` 분기 로직, 진단 로그 추가/제거 (commit 시점에
    제거됨)
- `app/src/main/java/com/example/minseo3/LocalProgressRepository.java`
  — `Entry.lastRead` (savedLastRead 출처)
- `docs/2026-04-24-reader-jump-position-rules.md`
  — 진입 경로별 skipConflict 표 (이 fix 후에도 유효)
- `docs/2026-04-24-storage-layout.md`
  — `reading_progress.json` 스키마, fileHash 정의
