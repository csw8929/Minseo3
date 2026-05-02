package com.example.minseo3.tts;

import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * TTS 발화 서비스. 책 단위 페이지 자동 진행 + 외부에서 들어오는 페이지 점프 처리.
 *
 * <p>Phase 1 — 일반 bound service. startForeground / 알림 / MediaSession / AudioFocus
 * 모두 미구현. 화면 ON (ReaderFragment 가 visible) 동안에만 동작 보장. Phase 2 에서
 * foreground 전환.
 *
 * <p>자동 진행 루프: play → speakCurrentPage → tts.onDone → queue.setCurrentPage(next)
 * → queue listener → speakCurrentPage. 외부 skip(prev/next/직접 페이지 점프) 도 같은
 * 경로로 들어와 single source of truth 유지.
 *
 * <p>{@code lastSpokenPage} 가 진실의 원천 — onDone 의 utteranceId ("page-N") 의 N 이
 * lastSpokenPage 와 같으면 정상 진행, 다르면 stale (skip 으로 이미 점프함) 으로 ignore.
 */
public class TtsPlaybackService extends Service {

    private static final String TAG = "TtsPlaybackService";
    static final String UTTERANCE_PREFIX = "page-";

    public static final int STATE_IDLE = 0;
    public static final int STATE_PLAYING = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_STOPPED = 3;

    public interface StateListener {
        void onStateChanged(int state);
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TtsPlaybackQueue queue = TtsPlaybackQueue.get();
    private final CopyOnWriteArraySet<StateListener> stateListeners = new CopyOnWriteArraySet<>();

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean pendingPlay = false;
    private int lastSpokenPage = -1;
    private int state = STATE_IDLE;
    private float speechRate = 1.0f;

    public class LocalBinder extends Binder {
        public TtsPlaybackService getService() { return TtsPlaybackService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        queue.addListener(queueListener);
        initTts();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        queue.removeListener(queueListener);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
        setState(STATE_STOPPED);
        super.onDestroy();
    }

    // ── Public API (via Binder) ──────────────────────────────────────────────

    public void play() {
        if (!queue.isLoaded()) return;
        if (state == STATE_PLAYING) return;
        if (!ttsReady) {
            pendingPlay = true;
            // ttsReady 가 되면 OnInitListener 가 flushPendingPlay 호출.
            return;
        }
        setState(STATE_PLAYING);
        speakCurrentPage();
    }

    public void pause() {
        pendingPlay = false;
        if (state != STATE_PLAYING) {
            // 이미 정지 / 일시정지 — idempotent.
            if (state != STATE_PAUSED) setState(STATE_PAUSED);
            return;
        }
        if (tts != null) tts.stop();
        setState(STATE_PAUSED);
    }

    public void stop() {
        pendingPlay = false;
        if (tts != null) tts.stop();
        setState(STATE_STOPPED);
    }

    /** 사용자가 외부에서 페이지를 직접 이동 (탭/볼륨 키/시크바). 재생 중이면 새 페이지부터 재발화. */
    public void onPageMovedExternally() {
        if (state == STATE_PLAYING) {
            // queue.currentPage 는 이미 외부에서 갱신됨 → 우리는 재발화만.
            // (내부적으론 queueListener 도 같은 신호를 받지만, 외부 호출 경로에서는 명시적으로 호출.)
            if (queue.getCurrentPage() != lastSpokenPage) {
                speakCurrentPage();
            }
        }
    }

    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        if (tts != null) tts.setSpeechRate(rate);
    }

    public int getState() { return state; }

    public void addStateListener(StateListener l) { stateListeners.add(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }

    // ── Queue listener — 자동 진행 + 외부 페이지 변경 모두 이 경로 ──────────

    private final TtsPlaybackQueue.Listener queueListener = new TtsPlaybackQueue.Listener() {
        @Override public void onPageChanged(int page) {
            // 재생 중이고 lastSpokenPage 와 다르면 → 새 페이지부터 재발화.
            if (state == STATE_PLAYING && page != lastSpokenPage) {
                speakCurrentPage();
            }
        }
        @Override public void onLoadChanged() {
            // Phase 1: no-op. Phase 2 에서 fileHash 미스매치 감지 후 stopSelf 추가.
        }
    };

    // ── Internals ────────────────────────────────────────────────────────────

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init failed: " + status);
                ttsReady = false;
                stopSelf();
                return;
            }
            int langResult = tts.setLanguage(Locale.KOREAN);
            if (langResult == TextToSpeech.LANG_MISSING_DATA
                    || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            // API 34+ FGS_MEDIA_PLAYBACK 컴플라이언스용 — Phase 1 에선 foreground 안 쓰지만
            // 미디어 음성으로 인식되도록 미리 세팅. Phase 2 전환 시 추가 작업 없이 동작.
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            tts.setSpeechRate(speechRate);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {
                    handleUtteranceDone(utteranceId);
                }
                @Override public void onError(String utteranceId) {
                    Log.w(TAG, "TTS error utterance=" + utteranceId);
                    mainHandler.post(() -> setState(STATE_PAUSED));
                }
            });
            ttsReady = true;
            if (pendingPlay) {
                pendingPlay = false;
                mainHandler.post(this::flushPendingPlay);
            }
        });
    }

    private void flushPendingPlay() { play(); }

    /** TTS 스레드 / main 어디서든 호출 가능. tts.speak 자체는 thread-safe. */
    private void speakCurrentPage() {
        if (!ttsReady || tts == null) return;
        int page = queue.getCurrentPage();
        String pageText = queue.getPageText(page);
        if (pageText.isEmpty()) return;
        lastSpokenPage = page;
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM,
                String.valueOf(AudioManager.STREAM_MUSIC));
        tts.speak(pageText, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_PREFIX + page);
    }

    /** TTS 스레드에서 호출됨. */
    private void handleUtteranceDone(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith(UTTERANCE_PREFIX)) return;
        int spoken;
        try {
            spoken = Integer.parseInt(utteranceId.substring(UTTERANCE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        // skip 으로 이미 다른 페이지로 점프했으면 stale onDone — 무시.
        if (spoken != lastSpokenPage) return;
        if (state != STATE_PLAYING) return;

        int next = spoken + 1;
        if (next >= queue.pageCount()) {
            // 책 끝 — 정지하고 서비스 종료.
            mainHandler.post(() -> {
                setState(STATE_STOPPED);
                stopSelf();
            });
            return;
        }
        // 페이지 갱신 — queue 가 main looper 로 listener 를 dispatch 하므로,
        // queueListener.onPageChanged 가 main 에서 speakCurrentPage 호출.
        queue.setCurrentPage(next);
    }

    private void setState(int newState) {
        if (state == newState) return;
        state = newState;
        for (StateListener l : stateListeners) {
            mainHandler.post(() -> l.onStateChanged(newState));
        }
    }
}
