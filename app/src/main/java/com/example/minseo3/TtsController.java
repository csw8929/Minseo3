package com.example.minseo3;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

public class TtsController {

    public interface Listener {
        void onReady();
        void onDone();   // current page finished — caller can advance to next page
        void onError();
    }

    private TextToSpeech tts;
    private boolean ready = false;
    private Listener listener;

    public TtsController(Context context, Listener listener) {
        this.listener = listener;
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.KOREAN);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                ready = true;
                if (listener != null) listener.onReady();
            } else {
                if (listener != null) listener.onError();
            }
        });

        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            @Override public void onDone(String utteranceId) {
                if (listener != null) listener.onDone();
            }
            @Override public void onError(String utteranceId) {
                if (listener != null) listener.onError();
            }
        });
    }

    public void speak(CharSequence text) {
        if (!ready || text == null) return;
        tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, "page");
    }

    public void stop() {
        if (tts != null) tts.stop();
    }

    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }
}
