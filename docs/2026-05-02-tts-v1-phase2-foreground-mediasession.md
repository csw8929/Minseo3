# TTS V1 Phase 2 — Foreground Service + MediaSession + AudioFocus

작성일: 2026-05-02

## 배경

Phase 1 (`refactor(tts): TtsController → TtsPlaybackService 이전 + 자동 진행 수정`, 커밋 `571b5ee`) 에서 자동 진행 결함은 잡혔으나, 화면 OFF / 앱 백그라운드 / 폴드 닫힘 상태에서는 음성이 끊김. Phase 2 의 목적은 그 한계를 풀고 잠금화면 컨트롤까지 정착.

설계 문서: `~/.gstack/projects/Minseo3/USER-main-design-20260502-203739.md` (Approach A — 직접 만든 MediaSession + ForegroundService).

## 변경 요약

### 의존성

- `gradle/libs.versions.toml` + `app/build.gradle.kts` — `androidx.media:media:1.7.0` 추가 (`MediaSessionCompat`, `PlaybackStateCompat`, `MediaButtonReceiver`, `MediaStyle` 노티). media3 는 끌어오지 않음 (도메인 미스매치, APK 비대화 회피).

### 매니페스트

- 권한 3개: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (API 34+), `POST_NOTIFICATIONS` (API 33+ 런타임 요청).
- 서비스에 `android:foregroundServiceType="mediaPlayback"` + `<intent-filter>` MEDIA_BUTTON.
- `<receiver>` `androidx.media.session.MediaButtonReceiver` (헤드셋/Bluetooth A2DP 미디어 키 라우팅).

### 새 파일

- `tts/TtsNotificationBuilder.java` — 채널 생성 + `Notification.MediaStyle` 빌더 + placeholder 노티.
  - 채널 ID `tts_playback`, importance LOW (소리 진동 없음, 잠금화면 표시).
  - Title: 책 제목 (`displayTitle`), Text: `(currentPage+1) / pageCount쪽`.
  - **V1 default**: play/pause 단일 액션 (OQ 8 의 보수 안). ⏮ ⏭ 는 사용자 피드백 후 검토.
  - PlaybackStateCompat 의 `setActions` 는 SKIP_NEXT/PREV 도 항상 포함 — 헤드셋 미디어 키 라우팅용.
  - `setVisibility(VISIBILITY_PUBLIC)` 로 잠금화면 표시.
  - `setOngoing(playing)` — 재생 중엔 swipe 로 dismiss 차단.
- `res/drawable/ic_tts_notification.xml` — small 노티 아이콘 (Material volume_up 단순화, 24dp 흰색).

### `tts/TtsPlaybackService.java` 보강

- `MediaSessionCompat` 생성 + Callback (`onPlay/onPause/onStop/onSkipToNext/onSkipToPrevious`) → 서비스 자체 메서드로 라우팅. 잠금화면/노티/헤드셋 모든 컨트롤이 동일 코드 경로.
- AudioFocus:
  - API 26+ `AudioFocusRequest.Builder` (USAGE_MEDIA, CONTENT_TYPE_SPEECH, AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).
  - API 24-25 deprecated `requestAudioFocus(listener, STREAM_MUSIC, ...)` 폴백.
  - `LOSS_TRANSIENT[_CAN_DUCK]` → `wasPlayingBeforeFocusLoss=true; pause()`. `GAIN` 이면 자동 `play()`.
  - `LOSS` (영구) → `stop()` (서비스 종료).
- `BECOMING_NOISY` BroadcastReceiver — 헤드폰 뽑힘 시 `pause()`. registerNoisyReceiver 는 play 시점, unregister 는 pause/stop/destroy.
- `onStartCommand` — startForegroundService 의 5초 데드라인 회피 위해 즉시 `startForeground(NOTIF_ID, buildPlaceholder(...))` 호출. MEDIA_BUTTON 인텐트면 `MediaButtonReceiver.handleIntent(mediaSession, intent)` 로 세션에 라우팅. `START_NOT_STICKY` 반환 (TTS 상태는 인텐트로 복원 불가).
- `play()`:
  1. AudioFocus 요청 — 실패 시 PAUSED 로 표시
  2. MediaSession setActive(true) + state PLAYING
  3. registerNoisyReceiver
  4. updateNotification(PLAYING)
  5. speakCurrentPage
- `pause()`: tts.stop, unregisterNoisyReceiver, state PAUSED, **foreground 유지** (사용자 노티로 재개 가능).
- `stop()`: tts.stop, abandonFocus, session.setActive(false), stopForeground(REMOVE), stopSelf.
- 책 끝 도달: handleUtteranceDone 에서 main 으로 stop 위임.
- 노티 컨텐츠 인텐트: BookListActivity 를 SINGLE_TOP 으로 → 사용자 듣던 책으로 복귀.

### `ReaderFragment.java`

- `onCreate` 신설: `registerForActivityResult(RequestPermission)` — POST_NOTIFICATIONS 응답 후 `actuallyPlayTts()` 호출.
- `toggleTts`: pause 분기는 즉시 처리. play 분기는 첫 재생 시 (notifPermAsked false) 권한 체크 → 미부여 시 1회 launch.
- `actuallyPlayTts`: `ContextCompat.startForegroundService(...)` (API 31+ 백그라운드 차단 시 try/catch → bound 로 폴백 + 토스트) + `ttsService.play()`.
- `onPause`: `ttsService.pause()` 호출 폐기 — Phase 2 부터 백그라운드 계속 재생.
- `onDestroyView`: unbind 만, `ttsService.stop()` 호출 안 함 — 서비스가 foreground 로 살아남아 잠금화면 노티에서 컨트롤.

## 라이프사이클 시나리오

| 시나리오 | 결과 |
|---|---|
| ▶ 첫 탭 (API 33+, 권한 미부여) | 권한 요청 다이얼로그 → (허용/거부 무관) 재생 시작. 거부 시 노티만 안 보임. |
| ▶ 탭 → 화면 OFF | 음성 계속, 잠금화면에 미디어 노티 ▮▮ |
| ▶ 탭 → 폴드/플립 닫음 | 음성 계속 (foreground service + AudioFocus 전체 가짐) |
| 재생 중 카카오톡 통화 | LOSS_TRANSIENT → 자동 일시정지. 통화 끝 → GAIN → 자동 재개. |
| 재생 중 유튜브 시작 | LOSS (영구) → 정지, 서비스 종료, 노티 사라짐. |
| 헤드폰 뽑힘 | BECOMING_NOISY → 자동 일시정지. 노티는 유지 (사용자 재개 가능). |
| 노티 ▮▮ 탭 | MediaSession.onPause 콜백 → pause(). |
| 노티 ▶ 탭 (일시정지 상태) | MediaSession.onPlay 콜백 → 같은 위치에서 재개. |
| 책 끝 페이지 음성 끝 | handleUtteranceDone → stop() → 서비스 종료, 노티 제거. |
| 사용자 다른 앱 들어감 (앱 백그라운드) | 음성 계속. ReaderFragment 살아있으면 binding 유지. |

## 의도적 한계 (Phase 3 에서)

- 속도 슬라이더 — 아직 UI 없음. 코드 상으로 `setSpeechRate(rate)` 는 이미 있고 prefs 만 연결되면 됨.
- "음성 엔진 변경" 버튼 — Phase 3 에서 시스템 TTS 설정 인텐트 연결.
- 엔진 변경 시 `tts.shutdown() + initTts()` 자동 재초기화 — Phase 3 에서.
- 잠금화면 노티의 ⏮ ⏭ 페이지 점프 액션 — V1 default 는 play/pause 만. 사용자 피드백 후 OQ 8 결정.

## 빌드 상태

- `./gradlew assembleDebug` → BUILD SUCCESSFUL (25s).
- minSdk 24 호환 (AudioFocus API 26+ 분기 처리 됨).
- 설치 / 실기기 테스트는 사용자 본인 리듬으로 (CLAUDE.md 워크플로우).

## 회귀 점검 포인트 (사용자 검증)

- [ ] ▶ 탭 → 화면 OFF → 음성 계속 + 잠금화면에 미디어 노티 등장
- [ ] 잠금화면 ▮▮ 탭 → 일시정지. ▶ 탭 → 같은 위치에서 재개
- [ ] 재생 중 유튜브/카카오톡 음성통화 → 자동 일시정지 (transient 면 자동 재개)
- [ ] Bluetooth 이어폰 뽑기 → 자동 일시정지
- [ ] 책 끝 도달 → 자동 정지, 노티 사라짐
- [ ] 폴드/플립 닫음 → 음성 계속 (R3CT70FY0ZP 폴드 / R3CX705W62D 플립 검증)
- [ ] 알림 권한 거부 시 — 음성은 재생되지만 잠금화면 노티 없음, 토스트 1회
- [ ] 회귀: 페이지 자동 진행, 사용자 탭 페이지 이동 + 재발화 (Phase 1 검증된 동작), NAS sync, 북마크, 글자 크기 변경, 볼륨 키, 콜드 오픈 빠르게
- [ ] 일부 OEM (구형 Samsung) 에서 화면 OFF 시 30분 연속 재생 끊김 여부 — 끊기면 OQ 7 발동 (PARTIAL_WAKE_LOCK 추가)

## OQ (Open Questions) 진행 상황

| OQ | 상태 |
|---|---|
| 1. v0.4.4.0 회귀 시점 | Phase 1 빌드 시점에 사용자 검증 완료 (회귀 무관, 새 아키텍처가 어차피 수정) |
| 2. AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 정책 | 일시정지로 진행. handleFocusLoss 한 메서드에 격리 — V2 변경 시 한 줄. |
| 7. WAKE_LOCK 추가 | Phase 2 검증 후 결정. 끊김 보고 시 추가. |
| 8. 잠금화면 ⏮ ⏭ | V1 default = play/pause 만. 사용자 피드백 후 검토. |
