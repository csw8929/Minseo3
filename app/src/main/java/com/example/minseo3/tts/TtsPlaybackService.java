package com.example.minseo3.tts;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media.session.MediaButtonReceiver;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * TTS 재생 서비스.
 *
 * <p>Phase 2 — Foreground Service + MediaSession + AudioFocus + BECOMING_NOISY.
 * 화면 OFF / 앱 백그라운드 / 폴드 닫힘 상태에서도 음성 계속 재생. 잠금화면 컨트롤 + 헤드셋 미디어
 * 키 동작. 통화/유튜브 끼어들기 시 자동 일시정지.
 *
 * <p>자동 진행 루프: play → speakCurrentPage → tts.onDone → queue.setCurrentPage(next)
 * → queue listener → speakCurrentPage. 외부 skip 도 같은 경로.
 *
 * <p>{@code lastSpokenPage} 가 진실의 원천 — onDone 의 utteranceId ("page-N") 의 N 이
 * lastSpokenPage 와 같으면 정상 진행, 다르면 stale (skip 으로 이미 점프함) 으로 ignore.
 *
 * <p>라이프사이클: ReaderFragment 가 ▶ 첫 탭에 startForegroundService + bindService.
 * 이후 unbind 해도 startForeground 상태 유지. 책 끝 / 사용자 정지 시 stopForeground +
 * stopSelf 로 종료.
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
    /** speakCurrentPage 호출마다 증가. utteranceId 에 인코딩되어 옛 utterance 의 stale onDone 차단. */
    private int currentSpeakGeneration = 0;
    @Nullable private String initialEngine;

    // ── Phase 2 추가 ──────────────────────────────────────────────────────────
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    @Nullable private AudioFocusRequest focusRequest;  // API 26+
    private boolean isForegroundActive = false;
    private boolean noisyReceiverRegistered = false;
    private boolean wasPlayingBeforeFocusLoss = false;

    public class LocalBinder extends Binder {
        public TtsPlaybackService getService() { return TtsPlaybackService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setupMediaSession();
        queue.addListener(queueListener);
        initTts();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // startForegroundService 의 5초 데드라인 — 즉시 placeholder 노티로 foreground 진입.
        if (!isForegroundActive) {
            startForeground(TtsNotificationBuilder.NOTIF_ID,
                    TtsNotificationBuilder.buildPlaceholder(this));
            isForegroundActive = true;
        }
        // Bluetooth/headset 미디어 키 라우팅
        if (intent != null && Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            MediaButtonReceiver.handleIntent(mediaSession, intent);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterNoisyReceiver();
        abandonAudioFocus();
        queue.removeListener(queueListener);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
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
            return;
        }
        if (!requestAudioFocus()) {
            Log.w(TAG, "AudioFocus 획득 실패 — pause 상태 유지");
            setState(STATE_PAUSED);
            updateNotificationFor(STATE_PAUSED);
            updatePlaybackState(STATE_PAUSED);
            return;
        }
        mediaSession.setActive(true);
        registerNoisyReceiver();
        setState(STATE_PLAYING);
        updatePlaybackState(STATE_PLAYING);
        updateNotificationFor(STATE_PLAYING);
        speakCurrentPage();
    }

    public void pause() {
        pendingPlay = false;
        if (state == STATE_PAUSED || state == STATE_IDLE) return;
        if (state == STATE_STOPPED) return;
        if (tts != null) tts.stop();
        unregisterNoisyReceiver();
        setState(STATE_PAUSED);
        updatePlaybackState(STATE_PAUSED);
        updateNotificationFor(STATE_PAUSED);
        // foreground 유지 — 사용자가 노티로 재개 가능.
    }

    public void stop() {
        pendingPlay = false;
        if (tts != null) tts.stop();
        unregisterNoisyReceiver();
        abandonAudioFocus();
        if (mediaSession != null) mediaSession.setActive(false);
        setState(STATE_STOPPED);
        updatePlaybackState(STATE_STOPPED);
        if (isForegroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            isForegroundActive = false;
        }
        stopSelf();
    }

    /** 사용자가 외부에서 페이지 직접 이동 (탭/시크바). 재생 중이면 새 페이지부터 재발화. */
    public void onPageMovedExternally() {
        if (state == STATE_PLAYING && queue.getCurrentPage() != lastSpokenPage) {
            speakCurrentPage();
        }
    }

    public void skipNext() {
        int next = queue.getCurrentPage() + 1;
        if (next >= queue.pageCount()) return;
        if (tts != null) tts.stop();
        queue.setCurrentPage(next);
        // queue 리스너가 main 에서 speakCurrentPage 호출.
    }

    public void skipPrev() {
        int prev = queue.getCurrentPage() - 1;
        if (prev < 0) return;
        if (tts != null) tts.stop();
        queue.setCurrentPage(prev);
    }

    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        if (tts == null) return;
        tts.setSpeechRate(rate);
        // 즉시 반영 — Android TTS 의 setSpeechRate 는 다음 speak() 호출부터 적용. 현재 발화는
        // 옛 속도로 끝까지 가서 사용자가 "한참 후에 반영" 으로 체감. 재생 중이면 같은 페이지를
        // 새 속도로 다시 발화 (QUEUE_FLUSH 가 옛 utterance 즉시 중단).
        if (state == STATE_PLAYING) speakCurrentPage();
    }

    /**
     * 시스템 TTS 설정에서 엔진을 바꿨다 돌아왔을 가능성 — 비교 후 다르면 TTS 인스턴스 재초기화.
     * Fragment.onResume 에서 호출.
     *
     * 재생 중이었으면 pendingPlay 로 표식 → init 후 자동 재개. AudioFocus 는 재초기화 동안
     * 그대로 보유 (잠깐 침묵).
     */
    public void checkEngineChange() {
        if (tts == null) return;
        String current = tts.getDefaultEngine();
        // null-safe — 일부 OEM/첫 부팅에서 null 가능.
        if (current == null || initialEngine == null) return;
        if (current.equals(initialEngine)) return;

        boolean wasPlaying = (state == STATE_PLAYING);
        tts.stop();
        tts.shutdown();
        tts = null;
        ttsReady = false;
        if (wasPlaying) {
            pendingPlay = true;
            updatePlaybackState(STATE_PAUSED);
            updateNotificationFor(STATE_PAUSED);
        }
        initTts();  // 새 인스턴스 — onInit 에서 ttsReady=true + flushPendingPlay.
    }

    public int getState() { return state; }

    public void addStateListener(StateListener l) { stateListeners.add(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }

    // ── MediaSession ─────────────────────────────────────────────────────────

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "TtsPlaybackService");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { TtsPlaybackService.this.play(); }
            @Override public void onPause() { TtsPlaybackService.this.pause(); }
            @Override public void onStop() { TtsPlaybackService.this.stop(); }
            @Override public void onSkipToNext() { TtsPlaybackService.this.skipNext(); }
            @Override public void onSkipToPrevious() { TtsPlaybackService.this.skipPrev(); }
        });
        updatePlaybackState(STATE_IDLE);
    }

    private void updatePlaybackState(int s) {
        if (mediaSession == null) return;
        int psState;
        switch (s) {
            case STATE_PLAYING: psState = PlaybackStateCompat.STATE_PLAYING; break;
            case STATE_PAUSED:  psState = PlaybackStateCompat.STATE_PAUSED;  break;
            case STATE_STOPPED: psState = PlaybackStateCompat.STATE_STOPPED; break;
            default:            psState = PlaybackStateCompat.STATE_NONE;
        }
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_STOP;
        PlaybackStateCompat ps = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(psState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build();
        mediaSession.setPlaybackState(ps);
    }

    private void updateNotificationFor(int s) {
        if (!isForegroundActive) return;
        Notification n = TtsNotificationBuilder.build(
                this, mediaSession, s,
                queue.displayTitle(), queue.getCurrentPage(), queue.pageCount());
        // 재생 → ongoing, 일시정지 → 사용자가 swipe 로 dismiss 가능.
        Context ctx = getApplicationContext();
        ((android.app.NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(TtsNotificationBuilder.NOTIF_ID, n);
    }

    // ── AudioFocus ───────────────────────────────────────────────────────────

    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // 영구 — 다른 앱이 우선권을 영구적으로 가져감 (예: 음악 재생 시작).
                mainHandler.post(this::stop);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 책 음성은 덕킹 의미 없음 — 일시정지. transient 면 GAIN 시 자동 재개.
                mainHandler.post(() -> {
                    if (state == STATE_PLAYING) {
                        wasPlayingBeforeFocusLoss = true;
                        pause();
                    }
                });
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false;
                    mainHandler.post(this::play);
                }
                break;
            default: break;
        }
    };

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener, mainHandler)
                    .build();
            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            }
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
        wasPlayingBeforeFocusLoss = false;
    }

    // ── BECOMING_NOISY (헤드폰 뽑힘 등) ────────────────────────────────────

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyReceiverRegistered = true;
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        try {
            unregisterReceiver(noisyReceiver);
        } catch (IllegalArgumentException ignored) {}
        noisyReceiverRegistered = false;
    }

    // ── Queue listener — 자동 진행 + 외부 페이지 변경 모두 이 경로 ──────────

    private final TtsPlaybackQueue.Listener queueListener = new TtsPlaybackQueue.Listener() {
        @Override public void onPageChanged(int page) {
            if (state == STATE_PLAYING && page != lastSpokenPage) {
                speakCurrentPage();
            }
            // 노티 페이지 번호 갱신 (재생/일시정지/정지 무관).
            updateNotificationFor(state);
        }
        @Override public void onLoadChanged() {
            updateNotificationFor(state);
        }
    };

    // ── TTS init / speak / utterance done ────────────────────────────────────

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
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            tts.setSpeechRate(speechRate);
            // 엔진 변경 감지용 — 인스턴스가 어떤 엔진으로 만들어졌는지 캐싱.
            initialEngine = tts.getDefaultEngine();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {
                    handleUtteranceDone(utteranceId);
                }
                @Override public void onError(String utteranceId) {
                    Log.w(TAG, "TTS error utterance=" + utteranceId);
                    mainHandler.post(() -> {
                        setState(STATE_PAUSED);
                        updatePlaybackState(STATE_PAUSED);
                        updateNotificationFor(STATE_PAUSED);
                    });
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

    private void speakCurrentPage() {
        if (!ttsReady || tts == null) return;
        int page = queue.getCurrentPage();
        String pageText = queue.getPageText(page);
        if (pageText.isEmpty()) return;
        lastSpokenPage = page;
        int gen = ++currentSpeakGeneration;
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM,
                String.valueOf(AudioManager.STREAM_MUSIC));
        // utteranceId = "page-N-gK" — N=페이지, K=generation. 같은 페이지 재발화 시 (속도 변경 등)
        // K 가 증가해 옛 utterance 의 stale onDone 을 식별 후 무시.
        tts.speak(pageText, TextToSpeech.QUEUE_FLUSH, params,
                UTTERANCE_PREFIX + page + "-g" + gen);
    }

    /** TTS 스레드에서 호출됨. */
    private void handleUtteranceDone(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith(UTTERANCE_PREFIX)) return;
        String body = utteranceId.substring(UTTERANCE_PREFIX.length());
        int dashG = body.indexOf("-g");
        if (dashG < 0) return;
        int spoken;
        int gen;
        try {
            spoken = Integer.parseInt(body.substring(0, dashG));
            gen = Integer.parseInt(body.substring(dashG + 2));
        } catch (NumberFormatException e) {
            return;
        }
        if (gen != currentSpeakGeneration) return;  // 재발화로 superseded — 옛 utterance.
        if (spoken != lastSpokenPage) return;
        if (state != STATE_PLAYING) return;

        int next = spoken + 1;
        if (next >= queue.pageCount()) {
            mainHandler.post(this::stop);
            return;
        }
        queue.setCurrentPage(next);
        // queue 리스너가 main 에서 speakCurrentPage + 노티 갱신.
    }

    private void setState(int newState) {
        if (state == newState) return;
        state = newState;
        for (StateListener l : stateListeners) {
            mainHandler.post(() -> l.onStateChanged(newState));
        }
    }
}
