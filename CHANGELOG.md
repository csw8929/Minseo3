# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions use 4-digit `MAJOR.MINOR.PATCH.MICRO`.

## [0.4.3.0] - 2026-04-26

### Added
- **굵은 글씨체 토글.** 설정 화면 글자 크기 슬라이더 바로 아래에 "굵은 글씨체" 스위치 추가. 켜면 `Typeface.DEFAULT_BOLD` 가 적용되어 뷰어 본문이 굵게 표시됨. 설정 값은 `reader_prefs` 에 영구 저장. 켜기/끄기 시 글자 크기 변경과 동일하게 전체 페이지 재계산 (bold 글자는 너비가 달라 페이지 경계가 바뀜). PaginationCache 파일명에 `_b0`/`_b1` suffix 를 추가해 bold/non-bold 캐시를 분리.

## [0.4.2.0] - 2026-04-26

### Added
- **볼륨 키로 페이지 이동.** 리더 화면 활성 + TTS 비활성 상태에서 VOLUME_UP=이전 페이지, VOLUME_DOWN=다음 페이지. TTS 재생 중엔 시스템에 위임돼 음량 조절로 동작. 다른 탭(내 책/즐겨찾기)에서도 시스템 기본 동작 유지. 길게 누름은 무시 (책 빠르게 넘어가는 방지) — 빠른 연속 press 는 기존 60ms debounce 가 자연 coalesce.

### Fixed
- **NAS 팝업 → 0% 시작 버그.** "다른 단말에서 X% 까지 읽었습니다" → "예" 선택 시, 탭에 stale local entry 가 있으면 명시적 NAS offset 이 무시되고 0% 부터 시작하던 회귀. `BookListActivity` 에 `currentBookFreshOpen` 플래그 추가 (회전 복원 vs fresh 진입 구분), `ReaderFragment.loadCurrentBookFromHost` 가 fresh open 시 host 의 startOffset 을 신뢰. 디버깅 기록은 `docs/2026-04-26-reader-fresh-open-offset-fix.md`.

## [0.4.1.0] - 2026-04-24

### Changed
- **앱 아이콘 리디자인.** 배경색 기본 보라(`#4527A0`) → 미드나잇 블루(`#1A1B2E`), 책 페이지 흰색 → 따뜻한 양피지(`#F5E2B8`), 바인딩 회색 → 가죽 갈색(`#8B6440`). 독서앱의 문학적 분위기에 맞는 아이콘으로 교체.
- **리더 상단 메뉴 버튼 개선.** 텍스트만 있던 설정/NAS 버튼을 아이콘(24dp) + 레이블(11sp) 조합으로 변경, 높이 48dp → 56dp. 커스텀 벡터 아이콘 4종 추가: `ic_settings`, `ic_cloud_sync`, `ic_volume_up`, `ic_format_list`.
- **리더 하단 버튼 아이콘 교체.** 구형 `@android:drawable/ic_media_play` / `ic_menu_revert` → 커스텀 `ic_volume_up` / `ic_format_list`. 시스템 기본 아이콘 의존성 제거.
- **overlay 알파 통일.** 상태 바·메뉴·하단 바 네 곳의 배경색을 `#CC000000` → `#D9000000` 으로 일관화.
- **Settings BottomSheet 드래그 핸들 추가.** 상단에 36×4dp 핸들 뷰 추가 — 바텀 시트임을 시각적으로 명시.

## [0.4.0.0] - 2026-04-24

### Added
- **한국어 .txt 인코딩 자동 감지.** BOM 감지 → UTF-8 strict 검증 → CP949/EUC-KR 폴백 순서로 자동 판별. 기존에 깨지던 CP949 소설 파일 정상 표시. `PaginationCache.CACHE_VERSION` 2로 올려 구 offset 무효화.
- **글자 크기 슬라이더 실시간 프리뷰.** 슬라이더 드래그 중엔 현재 화면만 즉시 재렌더 (paginate 없음), 손가락을 뗄 때만 전체 페이지 재계산. 대용량 파일에서 로딩 서클 체감 대폭 감소. `PageView.setTextSizePx` + `SettingsBottomSheet.Listener.onSizePreview`.
- **pagination 취소 가능 + 진행률 표시.** `PageRenderer.paginate` 오버로드로 `AtomicBoolean cancelled` + `IntConsumer onProgress` 지원. 크기 변경 시 이전 작업 즉시 취소. 진행률 SeekBar 실시간 갱신.

### Fixed
- **탭 간 실시간 동기화.** 리더에서 저장한 진행률이 "내 책" 탭에 즉시 반영되지 않던 문제, 즐겨찾기 북마크 간헐적 stale 현상 수정. `LocalProgressRepository` / `BookmarksRepository` 공유 싱글턴 + `CopyOnWriteArrayList<Runnable>` 다중 리스너 도입.
- **리스트→리더 전환 시 chrome 점프 제거.** `openBook()` 에서 슬라이드 애니메이션 시작 전 AppBar GONE + system bars 숨김을 선제 적용, 애니메이션 종료 시점의 레이아웃 이중 점프 제거.

### Changed
- **리스트 상단 Toolbar 제거.** 56dp 높이의 빈 타이틀 Toolbar 삭제 — 리더 ↔ 리스트 전환 시 상단 chrome 위치 차이 ~96dp → ~40dp 감소.

## [0.3.0.0] - 2026-04-24

### Added
- **크로스 디바이스 북마크 V1.** 리더 하단에 노란 별(⭐) 아이콘 — 탭하면 현재 페이지
  북마크 즉시 토글 (BottomSheet 없음), 별은 북마크 상태면 채움/아니면 테두리.
  `Bookmark` 값 객체 + deterministic id (`sha1(fileHash + ':' + charOffset)` 16 hex) —
  두 기기가 같은 페이지 북마크를 각각 만들어도 같은 id 로 자동 수렴.
  Soft-delete (tombstone) 전파로 좀비 부활 방지. `BookmarksConflictResolver` 유닛
  테스트 6 건.
- **"즐겨찾기" 탭.** 두 섹션: "내 북마크" (모든 책 북마크 시간순 플랫 리스트, 탭하면
  해당 책 charOffset 으로 점프) + "다른 단말 진행" (내 기기 제외 NAS 읽기 기록).
  섹션 타이틀 옆에 항목 개수 · 리스트 항목에 진행률 % 표시.
- **리더 탭 스와이프 내비.** 즐겨찾기 ↔ 리더 좌우 스와이프. ViewPager2 의 숨김 3번째
  페이지로 `ReaderFragment` 통합 (구 `ReaderActivity` 제거) — Activity 전환의 pre-canned
  애니 한계를 벗어나 드래그 중 두 화면 동시 노출 감각 확보.
- **리더 풀스크린 + edge-to-edge.** 리더 페이지 진입 시 AppBar 숨김, status/nav bar 자동
  은닉, swipe-reveal 허용. 스와이프 중 AppBar 페이드 아웃. 하단에 현재 파일명 표시.
- **앱 전체 테마 통일.** 리더에서 바꾼 배경/글자색이 "내 책" / "즐겨찾기" 탭에도 즉시
  반영 (`ThemePrefs` 앱 전역 공유). 리스트 항목 / divider 색도 배경 휘도에 따라 자동 조정.
- **설정 → 탭기능 스위칭 토글.** 리더 좌/우 화면 탭의 전/후 이동 방향을 반대로 바꾸는
  옵션 (`reader_prefs.tap_swap`).
- **콜드 스타트 진입 규칙.** 런치 시 NAS 최신(타 기기) > Local 최신이면 "이어
  보시겠습니까?" 팝업, Local 최신이 이기면 마지막 종료가 리더 탭이었는지에 따라
  자동 리더 진입 or 리스트 유지. 회전 복원 시엔 스킵. `AppSessionPrefs.last_exit_mode` +
  `LocalProgressRepository.getMostRecent`.
- **NAS `pos_*.json` 실 삭제.** 즐겨찾기의 "다른 단말 진행" 항목 길게 눌러 NAS 에서 삭제
  (`SynologyFileStationRepository.delete`).
- **북마크 등록/해제/삭제 토스트 피드백.** "북마크 등록됨 / 해제됨 / 삭제됨" 표시.
- **작업 문서** (`docs/`). 저장 구조 (NAS / 로컬 파일 스키마), 리더 진입 시 점프 위치
  결정 규칙 (ConflictResolver + 콜드 스타트 런치 결정), NAS 안정화 디버그 기록.

### Changed
- `SynologyFileStationRepository` 가 새로 추출된 `SynologyDsmHelper` 의 공용 HTTP
  헬퍼 (SID 재시도, 에러 코드, 경로 정규화) 를 쓰도록 리팩터. Pos / 북마크 두 레포가
  공유 — 중복 제거.
- NAS 기본 경로 통일: `/소설/.minseo/` / `/video/.minseo/` → `/web/.minseo/`. 코드 전체
  + prefs 시드 + UI hint 일관.
- NAS 로그 관찰용 prefix 추가 (`SACH_NAS ...`). 모든 NAS read/write 경로에서
  dispatch · HTTP · 결과를 tag=`NasSync` 로 찍음 → `logcat | grep SACH_NAS` 로 한 번에.
- `apk.sh` — 연결 adb 디바이스 install/uninstall/clear data/logcat grep 메뉴 통합.
- 디버그 APK 파일명 `app-debug.apk` → `Minseo3.apk`.

### Fixed
- **리더에서 추가한 북마크가 즐겨찾기 탭에 안 뜨던 문제.** `ReaderFragment` 와
  `MyBookmarksFragment` 가 각자 `BookmarksRepository` 인스턴스를 만들어 in-memory
  캐시가 분리되어 있었음. `BookListActivity.getBookmarksRepo()` 로 단일 인스턴스 공유.
- **가로/세로 회전 시 리더 글이 사라지던 문제.** `onSaveInstanceState` / 복원 경로
  정비 + `BookListActivity.markConflictResolvedForCurrentBook` 로 NAS 충돌 다이얼로그
  재프롬프트 방지.
- **가로 모드에서 설정 BottomSheet 가 잘리던 문제.** `STATE_EXPANDED` 강제 +
  `setSkipCollapsed(true)` + `NestedScrollView` 로 펼침.
- **리더 전체화면에서 상단 status bar 영역이 비어있던 문제.** `CoordinatorLayout` +
  `ScrollingViewBehavior` 를 `LinearLayout` 수직으로 교체해 AppBar GONE 시 상단 margin
  제거.
- **APK 내 NAS 비밀번호 노출 주의** — `DsFileConfig.PASS` 는 `.gitignore` 로 git 공유만
  막고 APK 에는 포함됨. 외부 배포 전 `PASS = ""` 로 비우는 것이 **P0** (TODOS 참조).

### Tests / Build
- `testOptions { unitTests.isReturnDefaultValues = true }` — `android.util.Log` not-mocked
  런타임 예외로 터지던 JVM 단위 테스트 정상화.
- `BookmarksConflictResolverTest` 6 건 (id collision · tombstone · 재생성 · 이종 기기
  페이지네이션 · 경계 케이스).

## [0.2.0.0] - 2026-04-22

### Added
- NAS cross-device sync, end-to-end. "내 책" / "NAS" 두 탭의 책 목록.
  책 열 때 로컬 vs NAS 위치 비교 후 명백한 차이는 조용히 따르고 애매하면
  "이어 읽기" 다이얼로그. NAS 탭에서 진입하면 충돌 해결 스킵.
- LAN / DDNS 이중 URL. 같은 공유기 아래에서는 LAN probe → 내부 경로로 붙고,
  실패 시 DDNS 로 폴백. hairpin NAT 공유기에서도 정상 동작.
- `RemoteProgressRepository` 추상화 + Synology FileStation 구현 +
  `FakeRemoteProgressRepository` (JVM 단위 테스트용).
- `ConflictResolver` — 같은 세션 / NAS 최신 / 로컬 최신 / 애매한 충돌 네 가지
  케이스를 `SAME_SESSION_WINDOW_MS` · `SAME_SESSION_OFFSET_DIFF` 상수로 판정.
- `DsFileConfig` (gitignored) + `.sample` 템플릿 — 개인용 기본값을 로컬에만 두고
  리플렉션으로 시드. 비밀번호는 원칙적으로 포함하지 않고 NAS 설정 화면에서 입력
  (로컬 테스트 편의용 topUp 로직만 지원).
- NAS 설정 화면에 "연결 테스트 후 저장" 버튼 — 실제 DSM 로그인 성공 시 자동 저장.
- `device_id` — `Settings.Secure.ANDROID_ID` 앞 16자, 에뮬레이터 공유 ID 는
  UUID 로 폴백.

### Fixed
- Android API 28+ cleartext HTTP 차단 해제 (`usesCleartextTraffic="true"`) — LAN
  IP 경로(HTTP)로 붙기 위해 필요.
- DSM Download 엔드포인트의 HTML 404 응답을 JSON 파싱 시도 → `connected=false`
  캐스케이드 버그 수정 (본문이 `<` 로 시작하면 "파일 없음" 으로 간주).
- FileStation Upload 엔드포인트의 `_sid` URL 파라미터 요구 반영 (form field 단독
  전달 시 code 119).
- `DsFileConfig.PATH` 를 Synology 공유 폴더 루트부터 시작하는 경로로 (`/video/.minseo/`)
  — 임의 경로는 code 408 로 거부됨.

### Documented
- `docs/2026-04-22-nas-connection-debug.md` — 다섯 층 연쇄 실패 디버깅 기록.
- `docs/2026-04-22-reader-fixes-and-perf.md` — 리더 버그 / 성능 개선 정리
  (이전 PR #1 세션의 노트가 이제 트래킹됨).

## [0.1.0.0] - 2026-04-22

### Added
- Reader runs in fullscreen by default; system bars auto-reveal on swipe and re-hide.
- Top "리스트" tap returns to the book list while keeping the current book in the
  task stack — pressing back from the list returns to the book.
- NAS settings screen and reader top bar (status row + menu row).

### Fixed
- Status bar no longer overlaps the reader's custom top bar.

### Documented
- CLAUDE.md: Windows + Git Bash screencap workflow for the dual-display device
  (save-then-pull, `MSYS_NO_PATHCONV=1`, `;` chaining, multi-display IDs).
