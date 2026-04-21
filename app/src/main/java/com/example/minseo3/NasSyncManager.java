package com.example.minseo3;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * NAS position sync — placeholder until DsFileApiClient is ported from Minseo21.
 * push() and fetchHistory() are no-ops until NAS connection is configured.
 */
public class NasSyncManager {

    private static final String PREFS = "nas_prefs";
    private static final String KEY_ENABLED = "nas_enabled";

    private final SharedPreferences prefs;
    private boolean connected = false;

    public NasSyncManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Called after debounce fires: push current position to NAS. No-op if not connected. */
    public void push(String fileHash, String filePath, int charOffset, int totalChars) {
        if (!isEnabled() || !connected) return;
        // TODO: port DsFileApiClient from Minseo21
        // Write /소설/.minseo/pos_{fileHash}.json
    }

    /** Returns true if NAS is reachable. */
    public boolean isConnected() {
        return connected;
    }
}
