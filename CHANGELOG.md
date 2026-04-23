# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions use 4-digit `MAJOR.MINOR.PATCH.MICRO`.

## [0.3.0.0] - 2026-04-24

### Added
- 크로스 디바이스 북마크 V1. 리더 하단에 노란 별(⭐) 아이콘 — 탭하면 이 책의
  북마크 시트가 뜨고, 현재 페이지 북마크 추가/제거 토글 + 이 책 북마크 목록에서
  해당 charOffset 으로 바로 점프. 별은 현재 페이지가 북마크 상태면 채움, 아니면 테두리.
- "즐겨찾기" 탭 (기존 "NAS" 탭 리네임). 두 섹션: "내 북마크" (모든 책 북마크를
  시간순 플랫 리스트, 탭하면 해당 책 열고 charOffset 이동) + "다른 단말 진행"
  (다른 기기에서 올린 읽기 기록만, 내 기기는 제외).
- NAS 를 통한 북마크 양방향 동기화. 리더 열 때 `bm_{fileHash}.json` pull → 로컬과
  union 머지 → UI 자동 갱신. 북마크 토글/삭제 시 1 초 debounce 후 자동 push.
- 리스트 항목 길게 누르기 → 제거 메뉴 → 즉시 반영 (내 북마크, 다른 단말 진행 둘 다).
  다른 단말 항목 제거는 NAS 에서 실제 `pos_{fileHash}.json` 삭제까지 수행.
- `Bookmark` 값 객체 + deterministic id (`sha1(fileHash + ':' + charOffset)` 16 hex) —
  두 기기가 같은 페이지 북마크를 각각 만들어도 자동으로 같은 id 로 수렴, 중복 없음.
- Soft-delete (tombstone) 전파. 한 기기 삭제 → NAS → 다른 기기 pull union — 좀비
  레코드 부활 없이 확실히 제거.
- `BookmarksConflictResolver` union 로직 유닛 테스트 6 건 — id collision / tombstone /
  재생성 / 이종 기기 페이지네이션 / 경계 케이스.

### Changed
- `SynologyFileStationRepository` 가 새로 추출된 `SynologyDsmHelper` 의 공용 HTTP
  헬퍼 (SID 재시도, 에러 코드, 경로 정규화) 를 쓰도록 리팩터. Pos / 북마크 두 레포가
  같은 헬퍼 공유 — 중복 제거.
- 토글/삭제 시 토스트 · Snackbar 없음 — 별 아이콘 flip 과 리스트 갱신만으로 피드백.

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
