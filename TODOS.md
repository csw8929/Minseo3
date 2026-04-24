# TODOS

Items grouped by component, sorted by priority (P0 highest).
Completed items move to the bottom under `## Completed`.

---

## Reader

(none)

---

## NAS Sync

### 북마크 라벨/노트 (V2)
**Priority:** P3
**Why:** V1 북마크는 `createdAt` + 자동 프리뷰 40자만. "대락이 살아남아" 가치가
현 설계에는 없음 — 프리뷰만으로는 다른 페이지들과 구분이 잘 안 됨.
**Acceptance:** V2 에서 북마크 생성/편집 시 선택적으로 한 줄 라벨 입력. BottomSheet /
즐겨찾기 리스트에 라벨이 있으면 프리뷰 대신 라벨을 먼저 표시. 없으면 프리뷰 fallback.
스키마 `version: 2` 로 bump. V1 앱은 label 필드 무시 (lenient 정책으로 forward-compat).

### 북마크 tombstone purge (V2)
**Priority:** P3
**Why:** 크로스 디바이스 북마크 V1 이 soft-delete (tombstone) 로 좀비 레코드를
방지함. `deletedAt` 레코드가 `bookmarks.json` / `bm_*.json` 에 계속 쌓여 파일 크기
증가. V1 배포 후 북마크 사용 패턴이 잡히면 적정 주기 (예: 90일) 를 정해 purge.
**Acceptance:** V2 에서 `deletedAt` 이 설정된 레코드를 일정 기간 지나면 JSON 에서
물리 제거. 모든 기기가 pull-완료 이후에 purge 해야 좀비 부활 방지.

### 탭 (Galaxy Tab S9) 5G 경로 E2E 검증
**Priority:** P2
**Why:** 폰 LAN 경로는 실기기 성공. 외부 / 셀룰러 환경에서는 DDNS 로 폴백되는
경로가 실제로 작동하는지 아직 안 돌려봄. 탭에 같은 APK 설치는 완료, WiFi 끄고
5G 로 `연결 테스트 후 저장` 시도해 DDNS 5001 경로로 `success:true` 받는지 확인.
**Acceptance:** 탭에서 로그인 성공 + `/video/.minseo/` 에 탭 deviceId 로 새
`pos_*.json` 생성. 두 단말에 같은 파일명+크기 .txt 가 있으면 크로스 디바이스
이어 읽기 다이얼로그 확인.

### BookmarksRepository 유닛 테스트 (Robolectric 또는 생성자 refactor)
**Priority:** P3
**Why:** Plan-eng-review (2026-04-23) 에서 CRITICAL 한정으로 12 유닛 합의 —
ConflictResolver 4 는 v0.3.0.0 에 커밋됨 (실제로는 6건). Repository 8 테스트는
`Context.getFilesDir()` 의존 때문에 Robolectric 도입 또는 생성자에 `File` 주입 refactor
필요해서 별도 세션으로 밀었음. 현 V1 은 실기기 E2E 수동 검증으로 갈음.
**Acceptance:** `BookmarksRepositoryTest` — load corrupt JSON, 미래 버전 skip,
toggleAtPage 의 (empty/alive/tombstone 3 분기), anyInRange binary search,
replaceAll 이 캐시 무효화 + listener 호출 확인.

### `DsAuth` 의 전역 static 상태를 싱글턴으로
**Priority:** P3
**Why:** `DsAuth` 가 app-global static (`cfgBaseUrl`, `cachedSid` 등) — 여러
`NasSyncManager` 인스턴스가 같은 prefs 로 만들어지면 `initDsAuthFromPrefs` 의
idempotent 가드가 잡아주지만 구조적으로는 지저분함. Minseo21 ISSUE-002 처럼
Application 싱글턴 + 명시 주입이 깔끔.

---

## Build / Workflow

### Decide whether to delete `origin/dev`
**Priority:** P3
**Why:** `main` 이 이제 GitHub 기본 브랜치. `origin/dev` 는 낡은 tip 그대로.
삭제하든 장기 브랜치로 두든 결정 필요. 코드 영향 없고 정리만 하면 됨.

---

## Completed

### 크로스 디바이스 북마크 V1 (2026-04-24)
- 리더 하단 ⭐ → BottomSheet (토글 + 이 책 북마크 목록 + charOffset 점프).
- 즐겨찾기 탭 리네임 + "내 북마크" (cross-book) + "다른 단말 진행" 섹션 분리.
- NAS 양방향 동기화 (`RemoteBookmarksRepository` / `SynologyBookmarksRepository` /
  `BookmarksConflictResolver`), deterministic id + soft-delete + 1초 push debounce.
- `SynologyDsmHelper` 로 pos / 북마크 공용 HTTP 로직 DRY 추출.
- 리스트 long-press 삭제 (NAS `pos_*.json` 실제 삭제 포함).
- 6건 유닛 테스트 (`BookmarksConflictResolverTest`). BookmarksRepository 8개 테스트는
  Context 의존으로 별도 세션 (`BookmarksRepositoryTest` TODO 참조).
- **Completed:** v0.3.0.0 (2026-04-24)

### `gh auth login` — 자동 PR 생성 활성화 (2026-04-24)
- `gh auth status` 로 인증 확인됨 (csw8929 / ssh). `/ship` 에서 `gh pr create` 사용 가능.

### Phase 2 NAS 동기화 실기기 E2E (2026-04-22)
- 5층 실패 체인 해결 (hairpin NAT, cleartext HTTP, HTML 404 파싱, Upload SID URL 파라미터, 공유 폴더 경로).
- 폰(Galaxy Fold4) LAN 경로 검증 완료: `pos_*.json` NAS 업로드 + NAS 탭 표시.

### Move NasSyncManager.push off the main thread (2026-04-22)
- 싱글 스레드 daemon ExecutorService 로 디스패치.
- Phase 1~3 커밋 (`2b39d14`, `0b8fdc8`, `4e3bae4`) 에 포함되어 전체 repository
  인터페이스 + Synology 구현 + NAS 탭 UI 까지 확장됨.
