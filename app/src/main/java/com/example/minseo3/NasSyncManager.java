package com.example.minseo3;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import com.example.minseo3.Bookmark;
import com.example.minseo3.nas.DsAuth;
import com.example.minseo3.nas.RemoteBookmarksRepository;
import com.example.minseo3.nas.RemoteProgressRepository;
import com.example.minseo3.nas.RemotePosition;
import com.example.minseo3.nas.SynologyBookmarksRepository;
import com.example.minseo3.nas.SynologyFileStationRepository;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NAS position sync. Phase 2: Synology FileStation 구현 배선.
 * push/fetchOne/fetchAll 모두 실 NAS 에 붙어 작동.
 * LAN URL 이 주어지면 DsAuth 가 기동 시 LAN probe → 실패 시 DDNS 폴백.
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
    private static final String KEY_LAN_HOST   = "nas_lan_host";
    private static final String KEY_LAN_PORT   = "nas_lan_port";
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
        String  getLanHost();
        int     getLanPort();
        void    save(boolean enabled, String host, int port,
                     String user, String pass, String path,
                     String lanHost, int lanPort);
    }

    private final Prefs prefs;
    private final RemoteProgressRepository repo;
    private final RemoteBookmarksRepository bmRepo;
    private final ExecutorService networkExecutor;
    private volatile boolean connected = false;

    // Bookmark push debounce: 1s. Repeated toggles coalesce so only the last
    // state hits NAS. Per-fileHash so different books don't block each other.
    private static final long BOOKMARK_PUSH_DEBOUNCE_MS = 1000L;
    private final Map<String, Long> bookmarkPushScheduledAt = new HashMap<>();

    public NasSyncManager(Context context) {
        this(new SharedPrefsBackedPrefs(context),
                new SynologyFileStationRepository(computeDeviceId(context)),
                new SynologyBookmarksRepository(),
                defaultExecutor());
        Context app = context.getApplicationContext();
        seedDefaultsIfFirstLaunch(app);
        topUpPasswordFromConfigIfEmpty();
        topUpLanFromConfigIfEmpty();
        DsAuth.startNetworkMonitoring(app);
        initDsAuthFromPrefs(this.prefs);
        // 낙관적 초기값 — 활성화 되어 있으면 곧 push/fetch 시도하도록 true.
        // 실제 네트워크 실패 시 onError 콜백에서 false 로 떨어뜨린다.
        this.connected = this.prefs.isEnabled();
    }

    /**
     * 첫 실행 감지 (KEY_HOST 가 없음) 시 DsFileConfig 의 값으로 prefs 를 시드.
     * DsFileConfig.java 는 .gitignore 로 제외돼 있어 리플렉션으로 조회 — 없으면 조용히 스킵.
     * 비밀번호는 시드하지 않음 (보안).
     */
    private void seedDefaultsIfFirstLaunch(Context app) {
        SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (sp.contains(KEY_HOST)) return; // 이미 시드됐거나 사용자가 설정 저장한 상태

        String host = readCfgString("HOST", "");
        Integer port = readCfgInt("PORT", null);
        String user = readCfgString("USER", "");
        String path = readCfgString("PATH", "/web/.minseo/");
        String lanHost = readCfgString("LAN_HOST", "");
        Integer lanPort = readCfgInt("LAN_PORT", null);

        if (host.isEmpty() && user.isEmpty() && port == null) return; // DsFileConfig 없음 — 시드 안 함

        prefs.save(
                /*enabled*/ true,
                host,
                port != null ? port : 5000,
                user,
                /*pass 는 시드 안 함 — 사용자가 직접 입력*/ "",
                path,
                lanHost,
                lanPort != null ? lanPort : 5000);
        Log.i(TAG, "DsFileConfig 로 prefs 시드 완료 (host=" + host + ", lanHost=" + lanHost + ")");
    }

    /**
     * prefs 의 비밀번호가 비어 있고 DsFileConfig.PASS 가 존재하면 prefs 로 복사.
     * 테스트 편의 — 실제 공개 코드에서는 DsFileConfig.PASS 를 비워두면 이 블록이 no-op.
     */
    private void topUpPasswordFromConfigIfEmpty() {
        if (!prefs.getPass().isEmpty()) return;
        String cfgPass = readCfgString("PASS", "");
        if (cfgPass.isEmpty()) return;
        prefs.save(
                prefs.isEnabled(),
                prefs.getHost(),
                prefs.getPort(),
                prefs.getUser(),
                cfgPass,
                prefs.getPath(),
                prefs.getLanHost(),
                prefs.getLanPort());
        Log.i(TAG, "DsFileConfig.PASS 로 비어있던 비밀번호 보충");
    }

    /**
     * prefs 의 LAN host 가 비어 있고 DsFileConfig.LAN_HOST 가 존재하면 보충.
     * 기존 버전에서 업그레이드된 경우 — LAN 필드가 신규 추가되어 prefs 에 없음.
     */
    private void topUpLanFromConfigIfEmpty() {
        if (!prefs.getLanHost().isEmpty()) return;
        String cfgLanHost = readCfgString("LAN_HOST", "");
        if (cfgLanHost.isEmpty()) return;
        Integer cfgLanPort = readCfgInt("LAN_PORT", null);
        prefs.save(
                prefs.isEnabled(),
                prefs.getHost(),
                prefs.getPort(),
                prefs.getUser(),
                prefs.getPass(),
                prefs.getPath(),
                cfgLanHost,
                cfgLanPort != null ? cfgLanPort : 5000);
        Log.i(TAG, "DsFileConfig.LAN_HOST 로 비어있던 LAN 보충 (" + cfgLanHost + ")");
    }

    private static String readCfgString(String field, String fallback) {
        try {
            Class<?> c = Class.forName("com.example.minseo3.nas.DsFileConfig");
            Object v = c.getField(field).get(null);
            return v == null ? fallback : v.toString();
        } catch (Throwable ignored) { return fallback; }
    }

    private static Integer readCfgInt(String field, Integer fallback) {
        try {
            Class<?> c = Class.forName("com.example.minseo3.nas.DsFileConfig");
            Object v = c.getField(field).get(null);
            return v instanceof Integer ? (Integer) v : fallback;
        } catch (Throwable ignored) { return fallback; }
    }

    /** 테스트/DI용. */
    NasSyncManager(Prefs prefs, RemoteProgressRepository repo,
                   RemoteBookmarksRepository bmRepo, ExecutorService executor) {
        this.prefs = prefs;
        this.repo = repo;
        this.bmRepo = bmRepo;
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
        String lanUrl  = buildBaseUrl(p.getLanHost(), p.getLanPort());
        String user = p.getUser();
        String pass = p.getPass();
        String posDir = p.getPath();
        // Idempotent — 여러 NasSyncManager 인스턴스가 같은 prefs 로 만들어져도 cachedSid 가 살아남게.
        boolean same = baseUrl.equals(DsAuth.cfgBaseUrl)
                && lanUrl.equals(DsAuth.cfgLanUrl)
                && user.equals(DsAuth.cfgUser)
                && pass.equals(DsAuth.cfgPass)
                && posDir.equals(DsAuth.cfgPosDir);
        if (same) return;
        DsAuth.init(baseUrl, lanUrl, user, pass, /*basePath*/ "/", posDir);
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

    public boolean isEnabled()  { return prefs.isEnabled(); }
    public String  getHost()    { return prefs.getHost(); }
    public int     getPort()    { return prefs.getPort(); }
    public String  getUser()    { return prefs.getUser(); }
    public String  getPass()    { return prefs.getPass(); }
    public String  getPath()    { return prefs.getPath(); }
    public String  getLanHost() { return prefs.getLanHost(); }
    public int     getLanPort() { return prefs.getLanPort(); }

    public void save(boolean enabled, String host, int port,
                     String user, String pass, String path,
                     String lanHost, int lanPort) {
        prefs.save(enabled, host, port, user, pass, path, lanHost, lanPort);
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

    /** NAS 에서 pos_{fileHash}.json 삭제. "다른 단말 진행" 리스트에서 사용. */
    public void deletePosition(String fileHash, RemoteProgressRepository.Callback<Void> cb) {
        if (!isEnabled() || !connected) {
            cb.onError("NAS 미연결");
            return;
        }
        networkExecutor.execute(() -> repo.delete(fileHash, new RemoteProgressRepository.Callback<Void>() {
            @Override public void onResult(Void v) {
                connected = true;
                cb.onResult(null);
            }
            @Override public void onError(String message) {
                connected = false;
                cb.onError(message);
            }
        }));
    }

    // ── Bookmarks sync ──────────────────────────────────────────────────────

    /**
     * 1초 debounce push — 연속 토글은 마지막 상태만 업로드한다.
     * bookmarks 는 "호출 시점의 로컬 전체" 를 그대로 원격에 덮어쓰므로 stale snapshot 이어도 안전.
     */
    public void pushBookmarks(String fileHash, List<Bookmark> snapshot) {
        if (!isEnabled() || !connected || bmRepo == null) return;
        long now = System.currentTimeMillis();
        long scheduledAt = now + BOOKMARK_PUSH_DEBOUNCE_MS;
        synchronized (bookmarkPushScheduledAt) {
            bookmarkPushScheduledAt.put(fileHash, scheduledAt);
        }
        networkExecutor.execute(() -> {
            try { Thread.sleep(BOOKMARK_PUSH_DEBOUNCE_MS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }

            synchronized (bookmarkPushScheduledAt) {
                Long latest = bookmarkPushScheduledAt.get(fileHash);
                if (latest == null || latest.longValue() != scheduledAt) return; // superseded
                bookmarkPushScheduledAt.remove(fileHash);
            }
            bmRepo.push(fileHash, snapshot, new RemoteBookmarksRepository.Callback<Void>() {
                @Override public void onResult(Void v) { connected = true; }
                @Override public void onError(String msg) {
                    connected = false;
                    Log.w(TAG, "bm push failed: " + msg);
                }
            });
        });
    }

    /** 리더 onCreate 시 1회 pull. 비활성/미연결이면 빈 리스트 즉시 콜백. */
    public void pullBookmarks(String fileHash,
                              RemoteBookmarksRepository.Callback<List<Bookmark>> cb) {
        if (!isEnabled() || !connected || bmRepo == null) {
            cb.onResult(Collections.<Bookmark>emptyList());
            return;
        }
        networkExecutor.execute(() ->
                bmRepo.fetchOne(fileHash, new RemoteBookmarksRepository.Callback<List<Bookmark>>() {
                    @Override public void onResult(List<Bookmark> value) {
                        connected = true;
                        cb.onResult(value == null ? Collections.<Bookmark>emptyList() : value);
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
        @Override public boolean isEnabled()  { return sp.getBoolean(KEY_ENABLED, false); }
        @Override public String  getHost()    { return sp.getString(KEY_HOST, ""); }
        @Override public int     getPort()    { return sp.getInt(KEY_PORT, 5000); }
        @Override public String  getUser()    { return sp.getString(KEY_USER, ""); }
        @Override public String  getPass()    { return sp.getString(KEY_PASS, ""); }
        @Override public String  getPath()    { return sp.getString(KEY_PATH, "/web/.minseo/"); }
        @Override public String  getLanHost() { return sp.getString(KEY_LAN_HOST, ""); }
        @Override public int     getLanPort() { return sp.getInt(KEY_LAN_PORT, 5000); }
        @Override public void save(boolean enabled, String host, int port,
                                   String user, String pass, String path,
                                   String lanHost, int lanPort) {
            sp.edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .putString(KEY_HOST, host)
                    .putInt(KEY_PORT, port)
                    .putString(KEY_USER, user)
                    .putString(KEY_PASS, pass)
                    .putString(KEY_PATH, path)
                    .putString(KEY_LAN_HOST, lanHost)
                    .putInt(KEY_LAN_PORT, lanPort)
                    .apply();
        }
    }
}
