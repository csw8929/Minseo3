# 리더 버그 수정 및 성능 개선 — PR #1

작업일: 2026-04-22
PR: https://github.com/csw8929/Minseo3/pull/1 (merged)
브랜치: `worktree-fixfault` (삭제됨)
머지된 commit 5건.

---

## 1. 작업 배경

세션 시작 시점 사용자가 보고/요청한 4가지:

1. 설정에서 변경한 폰트 크기·배경색이 앱 재실행 시 초기화됨
2. 좌/우 빠른 연속 탭이 "버벅거리며" 페이지가 탭 횟수만큼 이동하지 않음
3. 책 진입 시 흰 화면이 잠시 보이고 나서 저장된 배경색으로 바뀜
4. 설정 시트의 5개 배경 테마 중 어떤 것이 현재 선택된 것인지 표시 없음
5. (분석 후 추가 발견) 큰 책 첫 페이지 표시까지 1~3초 지연 — 전체 텍스트 StaticLayout 빌드가 원인

이후 PR 셀프 리뷰에서 P1/P2/P3 9건이 추가로 도출되어 모두 반영.

---

## 2. 머지된 commit 요약

| # | SHA | 종류 | 제목 |
|---|---|---|---|
| 1 | `08fa821` | feat | 리더 폰트 크기·테마 색상 영구 저장 |
| 2 | `5b893d4` | fix  | 연속 탭 응답성 개선 (탭 누적 + 더블탭 지연 제거) |
| 3 | `0453cfe` | fix  | 첫 로드 시 흰색 깜빡임 제거 및 선택 테마 표시 추가 |
| 4 | `8d991e3` | perf | 페이지네이션 캐시 + 첫 페이지 스트리밍 렌더링 |
| 5 | `4404d0c` | fix  | PR 리뷰 피드백 반영 (P1/P2/P3 9건) |

---

## 3. 변경 파일 (PR 전체 누계)

```
app/src/main/java/com/example/minseo3/
├── PageRenderer.java          (+47 / 중복 제거 후)
├── PaginationCache.java       (신규)
├── ReaderActivity.java        (수정 다수)
├── SettingsBottomSheet.java   (+27)
app/src/main/res/
├── drawable/theme_circle_selected_ring.xml  (신규)
└── layout/activity_reader.xml               (id 추가, 하드코딩 색 제거)
```

---

## 4. 개발 내용 상세

### 4.1 폰트/배경색 영구 저장 (commit 1)

**원인**: `ReaderActivity`의 `textSizeSp / textColor / bgColor` 가 메모리 필드로만 존재. 변경 시 프로세스 메모리만 갱신, 디스크 미반영.

**수정**:
- `SharedPreferences("reader_prefs")` 도입 (NAS 설정과 동일한 패턴).
- `onCreate`에서 `loadFile(...)` **이전에** 저장값 로드 → 첫 페이지네이션부터 저장된 폰트 크기·색상 적용.
- `showSettings()`의 `onChanged` 콜백에서 변경 즉시 `apply()`로 비차단 저장. 시트 안에서 슬라이더를 움직이거나 테마 버튼을 누르는 매 순간 디스크에 반영됨.

키 정의:
```java
private static final String PREFS_NAME       = "reader_prefs";
private static final String PREF_TEXT_SIZE_SP = "text_size_sp";  // float
private static final String PREF_TEXT_COLOR   = "text_color";    // int (ARGB)
private static final String PREF_BG_COLOR     = "bg_color";      // int (ARGB)
```

---

### 4.2 연속 탭 응답성 개선 (commit 2)

**원인 두 가지**:
1. `GestureDetector.onSingleTapConfirmed`는 더블탭 감지 타임아웃(~300ms) 후 발화. 빠른 두 번째 탭은 더블탭으로 흡수되어 첫 탭이 아예 사라짐.
2. 탭마다 `displayPage()`가 즉시 호출되어 중간 페이지의 `StaticLayout` 빌드가 낭비됨.

**수정**:
- `onSingleTapConfirmed` → `onSingleTapUp` 교체. 더블탭 대기 없이 매 탭 즉시 인식.
- 탭 누적 패턴:
  ```java
  private int pendingPageDelta = 0;
  private final Runnable applyPageDeltaRunnable = () -> {
      int target = currentPage + pendingPageDelta;
      pendingPageDelta = 0;
      displayPage(target);
      if (ttsActive) speakCurrentPage();
  };

  private void requestPageMove(int delta) {
      pendingPageDelta += delta;       // 후속 commit 5에서 ±pageCount 클램프 추가
      mainHandler.removeCallbacks(applyPageDeltaRunnable);
      mainHandler.postDelayed(applyPageDeltaRunnable, 60);
  }
  ```
- 60ms 디바운스 동안 들어온 모든 탭(N번)이 누적되어 마지막에 한 번 N페이지 점프. 단발 탭의 체감 지연은 약 60ms로 사실상 즉시.

**부수 효과**:
- TTS 활성 중 빠른 N번 탭에서 `speakCurrentPage()`가 N번 호출되던 것이 1번으로 정리됨 (구 구현 대비 의도와 더 일치).

---

### 4.3 첫 로드 흰색 깜빡임 + 선택 테마 표시 (commit 3)

#### 흰색 깜빡임
**원인**:
- `activity_reader.xml` 루트 `FrameLayout`이 `android:background="#FFFFFF"`로 하드코딩.
- `PageView`의 `backgroundColor` 기본값도 흰색.
- 첫 `displayPage()`가 호출되기 전(=풀 페이지네이션 끝나기 전)까지는 저장된 다크 테마와 무관하게 흰 배경이 보임.

**수정**:
- 루트 `FrameLayout`에 `@+id/reader_root` 부여.
- `onCreate`의 prefs 로드 직후, **첫 프레임이 그려지기 전에** 루트 + PageView 배경을 즉시 적용:
  ```java
  findViewById(R.id.reader_root).setBackgroundColor(bgColor);
  pageView.setColors(textColor, bgColor);
  ```
- 후속 commit 5에서 XML의 하드코딩 `#FFFFFF` 자체를 제거 (의도 명확화).

#### 선택 테마 빨간 링
**원인**: 5개 원형 버튼 중 어떤 것이 현재 선택된 것인지 시각 표시 없음.

**수정**:
- 새 드로어블 `theme_circle_selected_ring.xml` (투명 채움 + 빨간색 #E53935 stroke 3dp).
- `SettingsBottomSheet`에서 5개 버튼 참조를 `themeButtons` 배열로 보관.
- 초기 진입 시·각 버튼 클릭 시 `updateSelectedThemeIndicator()` 호출 → `THEMES[i][0] == currentBgColor`인 한 버튼에만 `setForeground(selectedRing)` 적용.
- 후속 commit 5에서 Drawable 자체를 필드 캐싱하여 매 호출마다 재로드 방지.

---

### 4.4 페이지네이션 캐시 + 첫 페이지 스트리밍 (commit 4)

**진단**:
- 사용자 가설("인덱스/% 계산이 느린 것?")은 잘못. 정수 연산은 0.001ms 수준.
- 진짜 병목: `PageRenderer.paginate()`의 `StaticLayout.Builder.obtain(text, ...)` — 전체 책 텍스트(200KB~1MB)로 StaticLayout을 한 번에 빌드하는 데 1~3초 소요.

**전략**: 두 갈래 최적화.

#### (1) 페이지네이션 캐시 — `PaginationCache`
- 키: `(fileHash, sizeSp, widthPx, heightPx)`
- 값: `int[] pageOffsets` (binary)
- 위치: `context.getCacheDir() + /pagination/<fileHash>_<sizeSp>_<wxh>.bin`
- 포맷: `int CACHE_VERSION` + `int count` + `int[count]` (commit 5에서 버전 헤더 추가)
- 효과: 같은 책을 같은 폰트·화면으로 재오픈 시 페이지네이션 자체를 **스킵**, 캐시 로드 후 정식 페이지 즉시 표시.

#### (2) 첫 페이지 스트리밍 — `PageRenderer.computeFirstPageText`
- `startOffset`에서 약 20K 글자 윈도우만 substring 후 StaticLayout 빌드.
- 첫 페이지 분량(=`heightPx`를 초과하는 첫 라인 직전까지)만 잘라 반환.
- 캐시 miss 시 이 결과를 즉시 PageView에 그려 사용자에게 표시 → 풀 페이지네이션은 백그라운드에서 진행.

#### `paginate()` 신 흐름
```
paginationReady = false
seekBar 비활성

executor:
  cached = cache.load(...)
  if cached:
    pageRenderer.setOffsets(cached)
    main: paginationReady=true, displayPage(offsetToPage(startOffset))
    return
  else:
    firstPage = computeFirstPageText(...)
    main: setPage(firstPage), tvPageInfo="…", seekBar=0
    pageRenderer.paginate(...)  // BG full
    cache.save(...)
    main: paginationReady=true, displayPage(offsetToPage(startOffset))
```

#### `paginationReady` 게이트
풀 페이지네이션 완료 전에는 다음을 모두 차단:
- `requestPageMove()` — 좌/우 탭 무반응 (pageOffsets 미완성)
- `seekBar.setEnabled(false)` — 드래그 무반응
- `toggleTts()` — TTS 토글 무반응
- 초기 commit 4에서 `flushSaveNow()`도 차단했으나, **commit 5에서 `lastKnownOffset`을 사용해 저장은 계속하되 0으로 덮지 않도록 변경** (lastRead 갱신 보장).

#### 알려진 거동
- Phase 1(첫 페이지) → Phase 2(canonical 페이지) 전환 시 시작 위치가 살짝 위로 재정렬됨. `startOffset`(저장 위치) → `pageOffsets[k] ≤ startOffset`(canonical 페이지 시작). 사용자 입장에선 "이전 컨텍스트 몇 줄이 추가되며 페이지 시작 정렬"되는 형태로 자연스럽게 느껴짐.

---

### 4.5 PR 리뷰 피드백 반영 (commit 5)

자가 리뷰에서 도출된 9건을 한 commit으로 처리.

| # | 우선순위 | 항목 | 처리 |
|---|---|---|---|
| 1 | P1 | cache miss 중 폰트 크기 변경 시 startOffset 손실 | `lastKnownOffset` 필드 도입, `paginate(int)`/`displayPage(int)`/`showSettings`에서 사용 |
| 2 | P2 | phase 2 진행 중 size 변경 시 stale 렌더 | `paginateGeneration` 카운터, 3개 main-thread 콜백에서 myGen 비교 후 stale이면 return |
| 3 | P2 | 캐시 키가 `LINE_SPACING` 등 불포함 | `CACHE_VERSION=1` 헤더 추가, mismatch 시 null 반환 |
| 4 | P2 | `flushSaveNow`가 `!paginationReady`에서 통째 return | `lastKnownOffset` fallback으로 lastRead 갱신 보장 |
| 5 | P3 | XML 하드코딩 `#FFFFFF` 잔존 | 제거 |
| 6 | P3 | `setForeground`마다 Drawable 재생성 | `selectedRing` 필드 캐싱 |
| 7 | P3 | `computeFirstPageText` ↔ `paginate` 코드 중복 | `buildLayout` 헬퍼 추출 |
| 8 | P3 | `pendingPageDelta` 무한 누적 | `Math.max(-pageCount, Math.min(pageCount, ...))` 클램프 |
| 9 | P3 | 캐시 디렉토리 무한 증가 | `evictIfNeeded()`: `MAX_ENTRIES=200` 초과 시 mtime 오름차순 삭제 |

---

## 5. 데이터/저장소 추가 사항

### `SharedPreferences("reader_prefs")`
- `text_size_sp` (float, 기본 17)
- `text_color`  (int ARGB, 기본 0xFF222222)
- `bg_color`    (int ARGB, 기본 0xFFFFFFFF)

### `cacheDir/pagination/`
- 파일명: `<16hex_fileHash>_<sizeSpInt>_<widthPx>x<heightPx>.bin`
- 포맷:
  ```
  int  CACHE_VERSION  (현재 1)
  int  count
  int[count]  pageOffsets
  ```
- 항목 수 상한: 200 (LRU by mtime)

### 캐시 무효화 트리거
- `CACHE_VERSION` 증가 (코드 상수). `LINE_SPACING_*`, 폰트 패밀리, 패딩 등 레이아웃 영향 상수 변경 시 반드시 함께 올릴 것.

---

## 6. 테스트 절차 (디바이스)

빌드/설치:
```bash
./gradlew installDebug
```
대상: `SM-F936N` (Android 16)

| 시나리오 | 기대 결과 |
|---|---|
| 폰트 크기/배경색 변경 후 앱 재실행 | 변경값 유지 |
| 좌/우 영역 빠르게 5회 탭 | 한 번에 5페이지 점프, 중간 깜빡임 없음 |
| 다크 테마 저장 후 다른 책 진입 | 첫 프레임부터 다크 배경 (흰색 미노출) |
| 설정 시트 진입 | 현재 테마 원형 버튼에 빨간 링, 다른 테마 클릭 시 링 즉시 이동 |
| 큰 책 첫 오픈 | 첫 페이지 거의 즉시 (페이지 카운트 "…" → 1~3초 후 채워짐) |
| 동일 책 같은 폰트/화면 재오픈 | 캐시 히트, 페이지 카운트까지 즉시 |
| 페이지네이션 진행 중 좌/우 탭 | 무반응 (완료 후 정상) |
| 로딩 중 폰트 크기 변경 | 책 첫머리로 튕기지 않음 (현재 위치 보존) |
| 폰트 크기 빠르게 여러 번 변경 | 마지막 크기로 안착, 중간 stale 렌더 없음 |

---

## 7. 후속 작업 후보 (이 PR 범위 밖)

- 캐시 무효화: 레이아웃 상수 변경 시 `CACHE_VERSION` bump 누락 방지를 위해 상수에서 hash를 자동 계산하는 방안.
- 페이지네이션 진행 중 사용자 입력 차단에 대한 시각 피드백(작은 인디케이터).
- 다크 테마 변경 시 reader_root 배경도 동일 세션 내 즉시 갱신 (현재는 PageView가 모두 가려서 영향 없음).
- 폰트 크기 외 배경색 변경 시 cache 키 영향 분석 (현재 키에 미포함, 영향 없음 — 색은 그릴 때만 사용).

---

## 8. 참고

- 작업 워크트리: `D:\workspace\Minseo3\.claude\worktrees\fixfault` (작업 후 정리 가능: `git worktree remove .claude/worktrees/fixfault`)
- 머지 후 원격 브랜치 `worktree-fixfault` 삭제 완료.
- CLAUDE.md의 Windows + Git Bash screencap 워크플로우는 본 PR과 무관(이전 commit fa01f78에서 정리됨).
