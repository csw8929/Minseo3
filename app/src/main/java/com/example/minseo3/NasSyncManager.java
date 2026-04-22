package com.example.minseo3;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.minseo3.nas.FakeRemoteProgressRepository;
import com.example.minseo3.nas.RemoteProgressRepository;
import com.example.minseo3.nas.RemotePosition;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NAS position sync. Phase 1: repository 주입 구조 + Fake 구현 배선.
 * Phase 2에서 기본 repo가 SynologyFileStationRepository로 교체됨.
 */
public class NasSyncManager {

    private static final String TAG = "NasSync";

    private static final String PREFS_NAME = "nas_prefs";
    private static final String KEY_ENABLED = "nas_enabled";
    private static final String KEY_HOST    = "nas_host";
    private static final String KEY_PORT    = "nas_port";
    private static final String KEY_USER    = "nas_user";
    private static final String KEY_PASS    = "nas_pass";
    private static final String KEY_PATH    = "nas_path";

    /** prefs 접근을 뒤로 빼서 단위 테스트에서 대체 가능하게 함. */
    public interface Prefs {
        boolean isEnabled();
        String  getHost();
        int     getPort();
        String  getUser();
        String  getPass();
        String  getPath();
        void    save(boolean enabled, String host, int port,
                     String user, String pass, String path);
    }

    private final Prefs prefs;
    private final RemoteProgressRepository repo;
    private final ExecutorService networkExecutor;
    private boolean connected = false;

    public NasSyncManager(Context context) {
        this(new SharedPrefsBackedPrefs(context),
                new FakeRemoteProgressRepository(),
                defaultExecutor());
    }

    /** 테스트/DI용. */
    NasSyncManager(Prefs prefs, RemoteProgressRepository repo, ExecutorService executor) {
        this.prefs = prefs;
        this.repo = repo;
        this.networkExecutor = executor;
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "NasSync");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Settings passthrough ─────────────────────────────────────────────────

    public boolean isEnabled() { return prefs.isEnabled(); }
    public String  getHost()   { return prefs.getHost(); }
    public int     getPort()   { return prefs.getPort(); }
    public String  getUser()   { return prefs.getUser(); }
    public String  getPass()   { return prefs.getPass(); }
    public String  getPath()   { return prefs.getPath(); }

    public void save(boolean enabled, String host, int port,
                     String user, String pass, String path) {
        prefs.save(enabled, host, port, user, pass, path);
    }

    public boolean isConnected() { return connected; }

    /** 테스트 전용 — 연결 상태를 강제로 설정. */
    void setConnectedForTest(boolean value) { this.connected = value; }

    // ── Sync ─────────────────────────────────────────────────────────────────

    /**
     * 디바운스 후 호출되어 현재 위치를 NAS에 푸시. 비활성/미연결이면 즉시 return.
     * 호출 스레드는 Main이어도 안전 — 실제 업로드는 networkExecutor에서.
     */
    public void push(String fileHash, String filePath, int charOffset, int totalChars) {
        if (!isEnabled() || !connected) return;

        File f = new File(filePath);
        RemotePosition pos = new RemotePosition(
                f.getName(), f.length(),
                charOffset, totalChars,
                repo.deviceId(),
                System.currentTimeMillis());

        networkExecutor.execute(() ->
                repo.push(fileHash, pos, new RemoteProgressRepository.Callback<Void>() {
                    @Override public void onResult(Void v) { /* no-op */ }
                    @Override public void onError(String msg) { Log.w(TAG, "push failed: " + msg); }
                }));
    }

    /** "NAS" 탭 진입 시 호출. 비활성/미연결이면 빈 맵을 즉시 콜백. */
    public void fetchAll(RemoteProgressRepository.Callback<Map<String, RemotePosition>> cb) {
        if (!isEnabled() || !connected) {
            cb.onResult(Collections.<String, RemotePosition>emptyMap());
            return;
        }
        networkExecutor.execute(() -> repo.fetchAll(cb));
    }

    /** 책 열 때 1회 충돌 해결을 위해 호출. 비활성/미연결이면 null을 즉시 콜백. */
    public void fetchOne(String fileHash, RemoteProgressRepository.Callback<RemotePosition> cb) {
        if (!isEnabled() || !connected) {
            cb.onResult(null);
            return;
        }
        networkExecutor.execute(() -> repo.fetchOne(fileHash, cb));
    }

    // ── Default Prefs impl ───────────────────────────────────────────────────

    private static final class SharedPrefsBackedPrefs implements Prefs {
        private final SharedPreferences sp;
        SharedPrefsBackedPrefs(Context ctx) {
            this.sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        @Override public boolean isEnabled() { return sp.getBoolean(KEY_ENABLED, false); }
        @Override public String  getHost()   { return sp.getString(KEY_HOST, ""); }
        @Override public int     getPort()   { return sp.getInt(KEY_PORT, 5000); }
        @Override public String  getUser()   { return sp.getString(KEY_USER, ""); }
        @Override public String  getPass()   { return sp.getString(KEY_PASS, ""); }
        @Override public String  getPath()   { return sp.getString(KEY_PATH, "/소설/.minseo/"); }
        @Override public void save(boolean enabled, String host, int port,
                                   String user, String pass, String path) {
            sp.edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .putString(KEY_HOST, host)
                    .putInt(KEY_PORT, port)
                    .putString(KEY_USER, user)
                    .putString(KEY_PASS, pass)
                    .putString(KEY_PATH, path)
                    .apply();
        }
    }
}
