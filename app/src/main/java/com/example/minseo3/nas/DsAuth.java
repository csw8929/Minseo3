package com.example.minseo3.nas;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NAS 자격 증명 + 세션 + base URL 해석 소유. 단일 스레드 ExecutorService 공유.
 *
 * Minseo21에서 포팅되며 설계에 따라 다음 변경 적용:
 *   1) {@code DsFileApiClient.Callback<T>} → {@link RemoteProgressRepository.Callback}
 *   2) {@code DsFileStation.clearFileIdCache()} 호출 삭제 (Minseo3엔 DsFileStation 없음)
 *   3) {@code DsFileConfig} 정적 기본값 제거 — 반드시 {@link #init} 을 먼저 호출할 것
 */
public final class DsAuth {
    private static final String TAG = "NAS";

    static volatile String cachedSid     = null;
    static volatile String resolvedBase  = null;

    private static final AtomicBoolean networkMonitorStarted = new AtomicBoolean(false);
    private static volatile Network lastKnownNetwork = null;

    static final ExecutorService executor = Executors.newSingleThreadExecutor();
    static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── 런타임 인증 정보 (init()에서 주입) ─────────────────────────────────────
    public static volatile String cfgBaseUrl  = "";
    public static volatile String cfgLanUrl   = "";
    public static volatile String cfgUser     = "";
    public static volatile String cfgPass     = "";
    public static volatile String cfgBasePath = "/";
    public static volatile String cfgPosDir   = "/";

    private DsAuth() {}

    /** NAS 인증 정보 적용. cachedSid/resolvedBase 캐시 무효화. */
    public static void init(String baseUrl, String lanUrl, String user, String pass,
                            String basePath, String posDir) {
        cfgBaseUrl  = baseUrl  == null ? "" : baseUrl;
        cfgLanUrl   = lanUrl   == null ? "" : lanUrl;
        cfgUser     = user     == null ? "" : user;
        cfgPass     = pass     == null ? "" : pass;
        cfgBasePath = basePath == null ? "/" : basePath;
        cfgPosDir   = posDir   == null ? "/" : posDir;
        cachedSid    = null;
        resolvedBase = null;
        Log.i(TAG, "DsAuth.init baseUrl=" + cfgBaseUrl + " lanUrl=" + cfgLanUrl);
    }

    /** 네트워크 전환(5G↔WiFi) 감지하여 resolvedBase / SID 캐시 무효화. */
    public static void startNetworkMonitoring(Context ctx) {
        if (!networkMonitorStarted.compareAndSet(false, true)) return;
        ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager null — 네트워크 모니터링 비활성");
            return;
        }
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (lastKnownNetwork != null && !lastKnownNetwork.equals(network)) {
                        Log.i(TAG, "네트워크 전환 감지 → resolvedBase/SID 캐시 초기화");
                        resolvedBase = null;
                        cachedSid = null;
                    }
                    lastKnownNetwork = network;
                }
                @Override public void onLost(Network network) {
                    Log.i(TAG, "네트워크 끊김 → 캐시 초기화");
                    resolvedBase = null;
                    cachedSid = null;
                    lastKnownNetwork = null;
                }
            });
        } catch (Exception e) {
            networkMonitorStarted.set(false);
            Log.w(TAG, "startNetworkMonitoring 실패: " + e.getMessage());
        }
    }

    /** 로그인 (비동기). 성공 시 SID 콜백. 콜백은 메인 스레드에서 실행. */
    public static void login(RemoteProgressRepository.Callback<String> cb) {
        executor.execute(() -> {
            try {
                if (resolvedBase == null) {
                    resolvedBase = resolveBase();
                    Log.i(TAG, "resolvedBase=" + resolvedBase);
                }
                String url = resolvedBase + "/webapi/auth.cgi"
                        + "?api=SYNO.API.Auth&version=6&method=login"
                        + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                        + "&passwd=" + URLEncoder.encode(cfgPass, "UTF-8")
                        + "&session=FileStation&format=sid";
                String body = DsHttp.httpGet(url);
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("success", false)) {
                    String sid = json.getJSONObject("data").getString("sid");
                    cachedSid = sid;
                    Log.i(TAG, "login OK, SID=" + sid.substring(0, Math.min(8, sid.length())) + "…");
                    mainHandler.post(() -> cb.onResult(sid));
                } else {
                    int code = json.optJSONObject("error") != null
                            ? json.getJSONObject("error").optInt("code", -1) : -1;
                    String msg = "로그인 실패 (code=" + code + ")";
                    Log.e(TAG, msg);
                    mainHandler.post(() -> cb.onError(msg));
                }
            } catch (Exception e) {
                Log.e(TAG, "login exception", e);
                mainHandler.post(() -> cb.onError("NAS 연결 오류: " + e.getMessage()));
            }
        });
    }

    /** API 호출에 사용할 base URL — LAN 성공 시 LAN, 아니면 cfgBaseUrl. */
    public static String apiBase() {
        return resolvedBase != null ? resolvedBase : cfgBaseUrl;
    }

    /** executor 스레드에서만 호출 (probeUrl 이 네트워크 호출). */
    private static String resolveBase() {
        if (cfgLanUrl != null && !cfgLanUrl.isEmpty() && DsHttp.probeUrl(cfgLanUrl)) {
            Log.i(TAG, "LAN 연결: " + cfgLanUrl);
            return cfgLanUrl;
        }
        Log.i(TAG, "외부 연결: " + cfgBaseUrl);
        return cfgBaseUrl;
    }

    /** 동기 재로그인 — 성공 시 새 SID 반환, 실패 시 null (cachedSid 도 클리어). */
    static String reLoginSync() {
        try {
            String base = resolvedBase != null ? resolvedBase : cfgBaseUrl;
            if (base == null || base.isEmpty()) return null;
            String loginUrl = base + "/webapi/auth.cgi"
                    + "?api=SYNO.API.Auth&version=6&method=login"
                    + "&account=" + URLEncoder.encode(cfgUser, "UTF-8")
                    + "&passwd=" + URLEncoder.encode(cfgPass, "UTF-8")
                    + "&session=FileStation&format=sid";
            String body = DsHttp.httpGet(loginUrl);
            JSONObject json = new JSONObject(body);
            if (json.optBoolean("success", false)) {
                String sid = json.getJSONObject("data").getString("sid");
                cachedSid = sid;
                Log.i(TAG, "reLoginSync OK");
                return sid;
            }
        } catch (Exception e) {
            Log.w(TAG, "reLoginSync 실패: " + e.getMessage());
        }
        cachedSid = null;
        return null;
    }

    /** 동기 SID 보장 — 캐시된 SID 있으면 반환, 없으면 로그인 시도. 실패 시 null. */
    static String ensureSidSync() {
        if (cachedSid != null) return cachedSid;
        if (resolvedBase == null) resolvedBase = resolveBase();
        return reLoginSync();
    }
}
