package com.example.minseo3;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.example.minseo3.nas.DsAuth;
import com.example.minseo3.nas.RemoteProgressRepository;
import com.example.minseo3.nas.RemotePosition;
import com.example.minseo3.nas.SynologyFileStationRepository;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NAS position sync. Phase 2: Synology FileStation 구현 배선.
 * push/fetchOne/fetchAll 모두 실 NAS 에 붙어 작동.
 */
public class NasSyncManager {

    private static final String TAG = "NasSync";

    private static final String PREFS_NAME = "nas_prefs";
    private static final String KEY_ENABLED    = "nas_enabled";
    private static final String KEY_HOST       = "nas_host";
    private static final String KEY_PORT       = "nas_port";
    private static final String KEY_USER       = "nas_user";
    private static final String KEY_PASS       = "nas_pass";
    private static final String KEY_PATH       = "nas_path";
    private static final String KEY_DEVICE_ID  = "nas_device_id";

    /** ANDROID_ID가 이 값이면 에뮬레이터/비정상 단말 → UUID 폴백. */
    private static final String BAD_ANDROID_ID = "9774d56d682e549c";

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
    private volatile boolean connected = false;

    public NasSyncManager(Context context) {
        this(new SharedPrefsBackedPrefs(context),
                new SynologyFileStationRepository(computeDeviceId(context)),
                defaultExecutor());
        Context app = context.getApplicationContext();
        DsAuth.startNetworkMonitoring(app);
        initDsAuthFromPrefs(this.prefs);
        // 낙관적 초기값 — 활성화 되어 있으면 곧 push/fetch 시도하도록 true.
        // 실제 네트워크 실패 시 onError 콜백에서 false 로 떨어뜨린다.
        this.connected = this.prefs.isEnabled();
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

    private void initDsAuthFromPrefs(Prefs p) {
        String baseUrl = buildBaseUrl(p.getHost(), p.getPort());
        String user = p.getUser();
        String pass = p.getPass();
        String posDir = p.getPath();
        // Idempotent — 여러 NasSyncManager 인스턴스가 같은 prefs 로 만들어져도 cachedSid 가 살아남게.
        boolean same = baseUrl.equals(DsAuth.cfgBaseUrl)
                && user.equals(DsAuth.cfgUser)
                && pass.equals(DsAuth.cfgPass)
                && posDir.equals(DsAuth.cfgPosDir);
        if (same) return;
        DsAuth.init(baseUrl, /*lanUrl*/ "", user, pass, /*basePath*/ "/", posDir);
    }

    /** 외부에서 현재 단말의 deviceId 를 읽을 수 있도록 노출 (ConflictResolver 용). */
    public String deviceId() { return repo.deviceId(); }

    /** "host" + port 를 http/https scheme 으로 조립. port 5001/443 → https, 그 외 http. */
    static String buildBaseUrl(String host, int port) {
        if (host == null || host.isEmpty()) return "";
        String h = host.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) {
            // 사용자가 이미 풀 URL 을 입력한 경우 — trailing slash 제거만.
            while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
            return h;
        }
        String scheme = (port == 5001 || port == 443) ? "https" : "http";
        return scheme + "://" + h + ":" + port;
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
        initDsAuthFromPrefs(prefs);
        this.connected = enabled; // 설정 저장 시점에 낙관적으로 리셋
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
                    @Override public void onResult(Void v) {
                        connected = true;
                    }
                    @Override public void onError(String msg) {
                        connected = false;
                        Log.w(TAG, "push failed: " + msg);
                    }
                }));
    }

    /** "NAS" 탭 진입 시 호출. 비활성/미연결이면 빈 맵을 즉시 콜백. */
    public void fetchAll(RemoteProgressRepository.Callback<Map<String, RemotePosition>> cb) {
        if (!isEnabled() || !connected) {
            cb.onResult(Collections.<String, RemotePosition>emptyMap());
            return;
        }
        networkExecutor.execute(() -> repo.fetchAll(new RemoteProgressRepository.Callback<Map<String, RemotePosition>>() {
            @Override public void onResult(Map<String, RemotePosition> value) {
                connected = true;
                cb.onResult(value);
            }
            @Override public void onError(String message) {
                connected = false;
                cb.onError(message);
            }
        }));
    }

    /** 책 열 때 1회 충돌 해결을 위해 호출. 비활성/미연결이면 null을 즉시 콜백. */
    public void fetchOne(String fileHash, RemoteProgressRepository.Callback<RemotePosition> cb) {
        if (!isEnabled() || !connected) {
            cb.onResult(null);
            return;
        }
        networkExecutor.execute(() -> repo.fetchOne(fileHash, new RemoteProgressRepository.Callback<RemotePosition>() {
            @Override public void onResult(RemotePosition value) {
                connected = true;
                cb.onResult(value);
            }
            @Override public void onError(String message) {
                connected = false;
                cb.onError(message);
            }
        }));
    }

    // ── Device ID ────────────────────────────────────────────────────────────

    private static String computeDeviceId(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String cached = sp.getString(KEY_DEVICE_ID, null);
        if (cached != null && !cached.isEmpty()) return cached;

        String androidId = Settings.Secure.getString(app.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        String id;
        if (androidId == null || androidId.isEmpty() || BAD_ANDROID_ID.equals(androidId)) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        } else {
            id = androidId.length() > 16 ? androidId.substring(0, 16) : androidId;
        }
        sp.edit().putString(KEY_DEVICE_ID, id).apply();
        return id;
    }

    // ── Default Prefs impl ───────────────────────────────────────────────────

    private static final class SharedPrefsBackedPrefs implements Prefs {
        private final SharedPreferences sp;
        SharedPrefsBackedPrefs(Context ctx) {
            this.sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
