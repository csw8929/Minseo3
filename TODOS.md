# TODOS

Items grouped by component, sorted by priority (P0 highest).
Completed items move to the bottom under `## Completed`.

---

## Reader

(none)

---

## NAS Sync

### 탭 (Galaxy Tab S9) 5G 경로 E2E 검증
**Priority:** P2
**Why:** 폰 LAN 경로는 실기기 성공. 외부 / 셀룰러 환경에서는 DDNS 로 폴백되는
경로가 실제로 작동하는지 아직 안 돌려봄. 탭에 같은 APK 설치는 완료, WiFi 끄고
5G 로 `연결 테스트 후 저장` 시도해 DDNS 5001 경로로 `success:true` 받는지 확인.
**Acceptance:** 탭에서 로그인 성공 + `/video/.minseo/` 에 탭 deviceId 로 새
`pos_*.json` 생성. 두 단말에 같은 파일명+크기 .txt 가 있으면 크로스 디바이스
이어 읽기 다이얼로그 확인.

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

### `gh auth login` — 자동 PR 생성 활성화
**Priority:** P3
**Why:** `gh` CLI 는 설치돼 있지만 (v2.91.0 at `C:\Program Files\GitHub CLI\`)
인증 안 돼서 `gh pr create` 사용 불가. 한 번 `gh auth login` 만 하면 이후
`/ship` 이나 수동 PR 생성이 자동화됨.

---

## Completed

### Phase 2 NAS 동기화 실기기 E2E (2026-04-22)
- 5층 실패 체인 해결 (hairpin NAT, cleartext HTTP, HTML 404 파싱, Upload SID URL 파라미터, 공유 폴더 경로).
- 폰(Galaxy Fold4) LAN 경로 검증 완료: `pos_*.json` NAS 업로드 + NAS 탭 표시.

### Move NasSyncManager.push off the main thread (2026-04-22)
- 싱글 스레드 daemon ExecutorService 로 디스패치.
- Phase 1~3 커밋 (`2b39d14`, `0b8fdc8`, `4e3bae4`) 에 포함되어 전체 repository
  인터페이스 + Synology 구현 + NAS 탭 UI 까지 확장됨.
