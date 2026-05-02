# TTS V1 Phase 3 — 읽기 속도 슬라이더 + 음성 엔진 변경 진입점

작성일: 2026-05-02

## 배경

Phase 1 (자동 진행 + 서비스 골격, `571b5ee`) + Phase 2 (Foreground + MediaSession + AudioFocus, `91943ed`) 로 핵심 통증 두 가지 (자동 진행 / 백그라운드) 해결. Phase 3 는 음성 품질 개선 — 사용자가 자기 단말의 더 좋은 한국어 TTS 엔진(Samsung / Google / Naver Clova 등) 으로 바꾸고, 속도를 자기 페이스에 맞게 조절.

설계 원칙: 앱 안에서 엔진/음성 리스트를 만들지 않고 OS 가 가장 잘 아는 영역(엔진 설치·선택)을 OS 에 위임. 앱은 속도만 책임짐 — 적게 만들고 많은 가치.

## 변경 요약

### 레이아웃

- `bottom_sheet_settings.xml` — 기존 설정들 아래에 구분선 + "TTS 음성" 섹션 신설:
  - "읽기 속도" 헤더 + SeekBar (느리게 ↔ 빠르게) + tv 표시 ("1.0x")
  - "음성 엔진 변경" outlined MaterialButton + 부가 설명 ("시스템 설정에서 더 좋은 한국어 TTS 엔진(Samsung / Google / Naver 등)을 설치·선택할 수 있습니다.")

### `SettingsBottomSheet.java`

- `Listener` 에 두 default 메서드 추가:
  - `onTtsRateChanged(float rate)` — 슬라이더 변경 즉시 발화 (debounce X, fragment 가 prefs 저장 + service.setSpeechRate).
  - `onOpenTtsEngineSettings()` — 버튼 클릭, fragment 가 시스템 인텐트 처리.
- `newInstance` 시그니처에 `float ttsRate` 추가 (기본 1.0f).
- 매핑: progress 0..15 ↔ rate 0.5x..2.0x, 0.1 단위 (`TTS_RATE_MIN + i * TTS_RATE_STEP`).
- float 누적 오차 방어 — `Math.round(r * 10f) / 10f`.

### `ReaderFragment.java`

- 새 prefs 키 `tts_speech_rate` (float, 기존 `reader_prefs` 안). 기본 1.0f.
- `onViewCreated` 에서 prefs 로드.
- `ServiceConnection.onServiceConnected` 에서 `ttsService.setSpeechRate(ttsRate)` 호출 — 서비스 default 1.0 을 사용자 값으로 덮음.
- `showSettings` 의 BottomSheet 생성 시 `ttsRate` 전달, 리스너에 `onTtsRateChanged` (prefs 저장 + service 적용) 와 `onOpenTtsEngineSettings` 추가.
- `openSystemTtsSettings()` 메서드 신설 — 멀티 폴백:
  1. `com.android.settings.TTS_SETTINGS` (AOSP 표준)
  2. `android.settings.TTS_SETTINGS` (일부 OEM 별칭)
  3. `Settings.ACTION_VOICE_INPUT_SETTINGS`
  4. `Settings.ACTION_SETTINGS`
  5. Toast: "기기 설정 → 일반 → 언어 → TTS 에서 변경하세요"
- `onResume` 에서 `ttsService.checkEngineChange()` 호출 — 시스템 TTS 설정에서 엔진 바꿨다 돌아왔을 때 자동 재초기화 트리거.

### `TtsPlaybackService.java`

- `initialEngine` 필드 (nullable String). `initTts` onInit success 에서 `tts.getDefaultEngine()` 캐싱.
- 신규 public 메서드 `checkEngineChange()`:
  - `tts.getDefaultEngine()` vs `initialEngine` 비교 (null-safe — OEM/첫 부팅에서 null 가능).
  - 다르면: `tts.stop() + shutdown()` + `ttsReady = false`. 재생 중이었으면 `pendingPlay = true` + UI 를 PAUSED 로 표시. `initTts()` 재호출 — onInit 후 자동 재개.
  - AudioFocus 는 재초기화 동안 유지 (잠깐 침묵 후 새 엔진 음성으로 재생).

## 라이프사이클 시나리오

| 시나리오 | 결과 |
|---|---|
| 설정 → 속도 슬라이더 1.5x 로 이동 | 다음 페이지 발화부터 1.5x. 앱 재시작 후에도 prefs 유지. |
| 설정 → "음성 엔진 변경" 탭 | 시스템 TTS 설정 화면 열림. Samsung/Google/Naver 등 선택 가능. |
| 시스템에서 엔진 변경 후 앱 복귀 | onResume → checkEngineChange → 재초기화. 재생 중이었으면 잠깐 침묵 후 새 엔진으로 자동 재개. |
| 시스템 설정 진입 인텐트 모두 미해결 | Toast: "기기 설정 → 일반 → 언어 → TTS 에서 변경하세요" |

## 빌드 상태

- `./gradlew assembleDebug` → BUILD SUCCESSFUL (3s, incremental).
- minSdk 24 호환 (TextToSpeech API 모두 24 이전부터 존재).

## 회귀 점검 포인트 (사용자 검증)

- [ ] 설정 → 읽기 속도 슬라이더 0.5x ~ 2.0x 가 0.1 단위로 움직이고 "1.5x" 식으로 표시
- [ ] 슬라이더 조정 후 다음 페이지 발화에 적용
- [ ] 앱 재시작 / 책 다시 열기 후에도 속도 유지
- [ ] "음성 엔진 변경" 버튼 → 시스템 TTS 설정 화면 열림 (Samsung 단말 R3CT70FY0ZP / R3CX705W62D 에서 폴백 동작 확인)
- [ ] 엔진 변경 후 앱 복귀 → 다음 발화에 새 엔진 적용 (또는 재생 중이었으면 잠깐 침묵 후 자동 재개)
- [ ] 회귀: Phase 1 + Phase 2 모든 동작 (자동 진행, 백그라운드, 잠금화면 컨트롤, AudioFocus, BECOMING_NOISY)

## OQ (Open Questions) 마감

| OQ | 상태 |
|---|---|
| 4. ContentIntent 가 듣던 책으로 복귀 | Phase 2 에서 해결 — BookListActivity SINGLE_TOP 으로 진입, ReaderFragment.onResume 의 loadCurrentBookFromHost 가 같은 책 자동 로드. |
| 5. 속도 슬라이더 UI 위치 | V1 default = 설정 BottomSheet 한 줄. V2 에서 미니 컨트롤 검토. |

## TTS V1 종료

세 페이즈 모두 완료. V2 후보 (사용자 피드백 후 결정):
- 잠금화면 ⏮ ⏭ 페이지 점프 액션 추가
- 문장 단위 하이라이트
- 슬립 타이머
- 한자/영어/숫자 사전 치환
- WAKE_LOCK (특정 OEM 끊김 보고 시)
