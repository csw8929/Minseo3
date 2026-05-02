# TTS V1 Phase 1 — 서비스 골격 + 자동 진행 수정

작성일: 2026-05-02

## 배경

- 기존 `TtsController` (67줄, ReaderFragment 안에서 직접 보유) 의 자동 진행이 **화면 ON 상태에서도** 한 페이지 이후 멈추는 결함 보고.
- 설계 문서: `~/.gstack/projects/Minseo3/USER-main-design-20260502-203739.md` (TTS V1 — 자동 진행 + 백그라운드 + 속도, Status: APPROVED, 품질 8.5/10)
- Phase 1 의 목적: TTS 를 ReaderFragment 안의 직접 보유에서 **별도 Service** 로 옮기고, 그 과정에서 자동 진행 버그를 동시 수정. 백그라운드 재생/Foreground/MediaSession/AudioFocus 는 Phase 2.

## 변경 요약

### 새 파일

- `app/src/main/java/com/example/minseo3/tts/TtsPlaybackQueue.java` — process-scoped 싱글톤. 본문 텍스트 + 페이지 분할 오프셋(int[]) + currentPage + fileHash + displayTitle. `load`/`replaceOffsets`/`setCurrentPage`/`getPageText`/`clear`. 모든 listener 콜백은 main looper 로 dispatch.
- `app/src/main/java/com/example/minseo3/tts/TtsPlaybackService.java` — `Service` 상속. `LocalBinder` 정적 inner. `TextToSpeech` 초기화, `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH)` 세팅 (Phase 2 의 FGS_MEDIA_PLAYBACK 컴플라이언스 미리 준비), `UtteranceProgressListener` 등록. **`lastSpokenPage` 필드가 진실의 원천** — onDone 의 utteranceId(`page-N`) 가 lastSpokenPage 와 같으면 정상 진행, 다르면 stale 로 무시. 책 끝 도달 시 `stopSelf()`.

### 매니페스트

- `<service android:name=".tts.TtsPlaybackService" android:exported="false" />` 추가. Phase 2 에서 `foregroundServiceType="mediaPlayback"` + 권한 3개 추가 예정.

### 삭제

- `app/src/main/java/com/example/minseo3/TtsController.java` — 핵심 init/listener 패턴은 `TtsPlaybackService.initTts()` 안으로 복사 이식. 직접 사용처 1곳 (ReaderFragment) 는 서비스 바인딩으로 교체.

### `ReaderFragment.java` 수정

- `private TtsController tts; private boolean ttsActive;` 폐기.
- `ServiceConnection ttsConn`, `TtsPlaybackService.StateListener ttsStateListener`, `TtsPlaybackQueue.Listener ttsQueueListener` 신설.
- `onViewCreated` 에서 `applicationContext.bindService(BIND_AUTO_CREATE)` + queue listener 등록.
- `toggleTts()` → `ttsService.play() / pause()`. 서비스 미연결 상태 빠른 탭은 `ttsPendingPlayAfterBind` 플래그로 큐.
- `displayPage(page)` 끝에 `ttsQueue.setCurrentPage(currentPage)` 추가 — 사용자 탭/시크바/자동 진행 모두 같은 경로.
- `loadFile` 의 partial 단계 후 `ttsQueue.load(text, offsets, fileHash, title)`, full 단계 후 `ttsQueue.replaceOffsets(fullOffsets, fullText)`.
- `paginate()` (in-memory, 글자 크기/굵기 변경) 의 캐시 hit / 새 paginate 양쪽 다 `ttsQueue.replaceOffsets`.
- `applyPageDeltaRunnable` 의 `if (ttsActive) speakCurrentPage()` 폐기. 대신 `if (isTtsActive()) ttsService.onPageMovedExternally()` — 서비스가 lastSpokenPage 와 currentPage 비교 후 재발화.
- `isTtsActive()` 는 `ttsState == STATE_PLAYING` 으로 변경.
- `onPause` — `tts.shutdown()` 대신 `ttsService.pause()` (Phase 1 한정 정책: 화면 안 보이면 음성 정지).
- `onDestroyView` — service stop + unbindService + listener 해제.
- 사용되지 않게 된 `private void nextPage()` 삭제.

## 자동 진행 수정 메커니즘

기존 결함: `onDone → mainHandler.post(nextPage)` 가 `displayPage(currentPage+1)` 만 호출하고 `speakCurrentPage` 는 호출하지 않음. 첫 페이지 끝에 페이지가 시각적으로 넘어가지만 음성은 멈춤.

새 흐름:

```
TextToSpeech onDone(utteranceId="page-N")  [TTS 스레드]
  ├─ parse N → spoken
  ├─ if (spoken != lastSpokenPage) ignore   ← skip race 차단
  ├─ if (state != STATE_PLAYING) ignore
  ├─ next = spoken + 1
  ├─ if (next >= pageCount) → stopSelf
  └─ queue.setCurrentPage(next)
      └─ notifyPageChanged → main looper post
            └─ service queueListener.onPageChanged(next)  [main 스레드]
                  └─ if (PLAYING && next != lastSpokenPage) speakCurrentPage()
                        └─ tts.speak(text, QUEUE_FLUSH, params, "page-" + next)
                              └─ lastSpokenPage = next
```

자동 진행과 외부 점프 (사용자 탭, 볼륨 키) 가 같은 queue → listener → speakCurrentPage 경로로 합류. utteranceId 의 페이지 번호와 lastSpokenPage 비교로 stale onDone 무시.

## Phase 1 의 의도적 한계 (Phase 2 에서 처리)

- `startForeground` 호출 안 함 → 화면 OFF / 앱 백그라운드 시 음성 정지. ReaderFragment.onPause 가 명시적으로 `service.pause()`.
- `Notification.MediaStyle` / 잠금화면 컨트롤 / 미디어 키 처리 없음.
- AudioFocus 요청/해제 없음 — 다른 미디어 앱과 동시 재생되거나 통화 시 끊기지 않을 수 있음.
- `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 권한 추가 안 함.
- 속도 슬라이더 / 시스템 TTS 설정 진입점 — Phase 3.

## 빌드 상태

- `./gradlew assembleDebug` → BUILD SUCCESSFUL (23s).
- 설치 / 실기기 테스트는 사용자가 본인 리듬으로 진행 (CLAUDE.md 워크플로우: 빌드까지만).

## 회귀 점검 포인트 (사용자 검증 필요)

- 화면 ON 상태에서 ▶ 탭 → 첫 페이지 음성 → 자동으로 다음 페이지 → 책 끝까지 진행
- TTS 활성 중 사용자가 화면 탭으로 페이지 이동 → 새 페이지부터 재발화
- TTS 활성 중 볼륨 키 — BookListActivity 가 인터셉트 안 하고 시스템 음량 조절로 위임 (`isTtsActive()` 분기 유지)
- 글자 크기 변경 → 재페이지네이션 → TTS 가 새 currentPage 로 자동 재발화 (또는 정지 후 재시작)
- 책 마지막 페이지 도달 → 자동 정지, 서비스 종료
- 다른 책 열기 → 새 책의 currentPage=0 부터 (TTS 비활성 상태로)
- 기존 기능 회귀 없음 — NAS sync, 북마크, 콜드 오픈 빠르게, 굵은 글씨 토글, 볼륨 키 페이지 이동
