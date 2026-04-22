package com.example.minseo3;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NAS position sync — placeholder until DsFileApiClient is ported from Minseo21.
 * push() and fetchHistory() are no-ops until NAS connection is configured.
 */
public class NasSyncManager {

    private static final String PREFS = "nas_prefs";
    private static final String KEY_ENABLED  = "nas_enabled";
    private static final String KEY_HOST     = "nas_host";
    private static final String KEY_PORT     = "nas_port";
    private static final String KEY_USER     = "nas_user";
    private static final String KEY_PASS     = "nas_pass";
    private static final String KEY_PATH     = "nas_path";

    private final SharedPreferences prefs;
    private boolean connected = false;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NasSync");
        t.setDaemon(true);
        return t;
    });

    public NasSyncManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    public boolean isEnabled()      { return prefs.getBoolean(KEY_ENABLED, false); }
    public String  getHost()        { return prefs.getString(KEY_HOST, ""); }
    public int     getPort()        { return prefs.getInt(KEY_PORT, 5000); }
    public String  getUser()        { return prefs.getString(KEY_USER, ""); }
    public String  getPass()        { return prefs.getString(KEY_PASS, ""); }
    public String  getPath()        { return prefs.getString(KEY_PATH, "/소설/.minseo/"); }

    public void save(boolean enabled, String host, int port, String user, String pass, String path) {
        prefs.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_HOST, host)
                .putInt(KEY_PORT, port)
                .putString(KEY_USER, user)
                .putString(KEY_PASS, pass)
                .putString(KEY_PATH, path)
                .apply();
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    /**
     * Called after debounce fires: push current position to NAS. No-op if not connected.
     * Safe to call from the main thread — actual upload runs on a background thread.
     */
    public void push(String fileHash, String filePath, int charOffset, int totalChars) {
        if (!isEnabled() || !connected) return;
        networkExecutor.execute(() -> {
            // TODO: port DsFileApiClient from Minseo21
            // Write {path}/pos_{fileHash}.json
        });
    }

    public boolean isConnected() { return connected; }
}
