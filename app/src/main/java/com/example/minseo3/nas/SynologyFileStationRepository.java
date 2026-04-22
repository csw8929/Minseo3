package com.example.minseo3.nas;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Synology FileStation 에 pos_*.json 을 읽고 쓰는 RemoteProgressRepository 구현.
 *
 * 모든 메서드는 호출 스레드에서 동기 실행 — {@code NasSyncManager.networkExecutor}가
 * 백그라운드로 감싸는 것을 전제로 한다.
 *
 * 주의: {@link DsAuth#init} 이 이미 호출된 상태여야 한다.
 */
public final class SynologyFileStationRepository implements RemoteProgressRepository {

    private static final String TAG = "NasSync";

    /** SID 만료/무효로 간주해 reLogin 후 1회 재시도하는 DSM 에러 코드. */
    private static final int[] SID_EXPIRY_CODES = {105, 106, 107, 119};

    /** FileStation.List 가 디렉토리 부재 시 내려주는 에러 코드. */
    private static final int ERROR_NO_SUCH_FILE = 408;

    private final String deviceId;

    /**
     * @param deviceId 기기 식별자 (ANDROID_ID 기반, {@code NasSyncManager} 가 생성)
     *
     * pos_*.json 을 둘 디렉토리는 {@link DsAuth#cfgPosDir} 에서 런타임에 읽는다
     * (사용자가 NAS 설정 화면에서 path 를 바꾸면 즉시 반영).
     */
    public SynologyFileStationRepository(String deviceId) {
        this.deviceId = deviceId;
    }

    @Override public String deviceId() { return deviceId; }

    private String resolvePosDir() { return normalizeDir(DsAuth.cfgPosDir); }

    // ── push ────────────────────────────────────────────────────────────────

    @Override
    public void push(String fileHash, RemotePosition pos, Callback<Void> cb) {
        try {
            byte[] body = pos.toJson().toString().getBytes(StandardCharsets.UTF_8);
            withSidAndRetry(sid -> {
                String url = DsAuth.apiBase() + "/webapi/entry.cgi";
                String resp = DsHttp.uploadFile(url, resolvePosDir(), "pos_" + fileHash + ".json", body, sid);
                JSONObject json = new JSONObject(resp);
                if (!json.optBoolean("success", false)) {
                    int code = errorCode(json);
                    throw new DsmException(code, "upload failed");
                }
                return null;
            });
            cb.onResult(null);
        } catch (Exception e) {
            Log.w(TAG, "push failed: " + e.getMessage());
            cb.onError(e.getMessage());
        }
    }

    // ── fetchOne ────────────────────────────────────────────────────────────

    @Override
    public void fetchOne(String fileHash, Callback<RemotePosition> cb) {
        String path = resolvePosDir() + "/pos_" + fileHash + ".json";
        try {
            RemotePosition pos = withSidAndRetry(sid -> {
                String url = DsAuth.apiBase() + "/webapi/entry.cgi"
                        + "?api=SYNO.FileStation.Download&version=2&method=download"
                        + "&path=" + URLEncoder.encode(path, "UTF-8")
                        + "&mode=open"
                        + "&_sid=" + sid;
                String body = DsHttp.httpGet(url);
                // Download 엔드포인트는 raw 바이트 — 우리 경우 JSON 문자열 그대로.
                // 단, 파일이 없거나 권한 오류면 {success:false, error:{code:...}} envelope 리턴.
                if (body.startsWith("{") && body.contains("\"success\"")) {
                    JSONObject envelope = new JSONObject(body);
                    if (!envelope.optBoolean("success", false)) {
                        int code = errorCode(envelope);
                        if (code == ERROR_NO_SUCH_FILE) return null;
                        throw new DsmException(code, "download failed");
                    }
                }
                JSONObject json = new JSONObject(body);
                return RemotePosition.fromJson(json);
            });
            cb.onResult(pos);
        } catch (Exception e) {
            Log.w(TAG, "fetchOne failed (" + fileHash + "): " + e.getMessage());
            cb.onError(e.getMessage());
        }
    }

    // ── fetchAll ────────────────────────────────────────────────────────────

    @Override
    public void fetchAll(Callback<Map<String, RemotePosition>> cb) {
        try {
            Map<String, RemotePosition> result = withSidAndRetry(sid -> {
                String listUrl = DsAuth.apiBase() + "/webapi/entry.cgi"
                        + "?api=SYNO.FileStation.List&version=2&method=list"
                        + "&folder_path=" + URLEncoder.encode(resolvePosDir(), "UTF-8")
                        + "&_sid=" + sid;
                String body = DsHttp.httpGet(listUrl);
                JSONObject envelope = new JSONObject(body);
                if (!envelope.optBoolean("success", false)) {
                    int code = errorCode(envelope);
                    if (code == ERROR_NO_SUCH_FILE) return new LinkedHashMap<>();
                    throw new DsmException(code, "list failed");
                }

                JSONArray files = envelope.getJSONObject("data").optJSONArray("files");
                Map<String, RemotePosition> map = new LinkedHashMap<>();
                if (files == null) return map;

                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    String name = f.optString("name", "");
                    if (!name.startsWith("pos_") || !name.endsWith(".json")) continue;
                    String hash = name.substring("pos_".length(), name.length() - ".json".length());
                    try {
                        RemotePosition pos = downloadPos(resolvePosDir() + "/" + name, sid);
                        if (pos != null) map.put(hash, pos);
                    } catch (Exception inner) {
                        Log.w(TAG, "fetchAll skip " + name + ": " + inner.getMessage());
                    }
                }
                return map;
            });
            cb.onResult(result);
        } catch (Exception e) {
            Log.w(TAG, "fetchAll failed: " + e.getMessage());
            cb.onError(e.getMessage());
        }
    }

    /** fetchAll 내부 루프 — SID 이미 있음, 에러 격리 호출 단위. */
    private RemotePosition downloadPos(String fullPath, String sid) throws Exception {
        String url = DsAuth.apiBase() + "/webapi/entry.cgi"
                + "?api=SYNO.FileStation.Download&version=2&method=download"
                + "&path=" + URLEncoder.encode(fullPath, "UTF-8")
                + "&mode=open"
                + "&_sid=" + sid;
        String body = DsHttp.httpGet(url);
        if (body.startsWith("{") && body.contains("\"success\"")) {
            JSONObject envelope = new JSONObject(body);
            if (!envelope.optBoolean("success", false)) return null;
        }
        return RemotePosition.fromJson(new JSONObject(body));
    }

    // ── SID expiry retry wrapper ─────────────────────────────────────────────

    private interface SidCall<T> { T call(String sid) throws Exception; }

    private <T> T withSidAndRetry(SidCall<T> call) throws Exception {
        String sid = DsAuth.ensureSidSync();
        if (sid == null) throw new Exception("NAS 로그인 실패");
        try {
            return call.call(sid);
        } catch (DsmException e) {
            if (!isSidExpiry(e.code)) throw e;
            Log.i(TAG, "SID 만료 감지 (code=" + e.code + ") → reLoginSync 재시도");
            String newSid = DsAuth.reLoginSync();
            if (newSid == null) throw new Exception("NAS 재로그인 실패");
            return call.call(newSid);
        }
    }

    private static boolean isSidExpiry(int code) {
        for (int c : SID_EXPIRY_CODES) if (c == code) return true;
        return false;
    }

    private static int errorCode(JSONObject envelope) {
        JSONObject err = envelope.optJSONObject("error");
        return err == null ? -1 : err.optInt("code", -1);
    }

    private static String normalizeDir(String dir) {
        if (dir == null || dir.isEmpty()) return "/";
        String d = dir.trim();
        if (d.endsWith("/") && d.length() > 1) d = d.substring(0, d.length() - 1);
        return d;
    }

    /** DSM envelope 의 에러 코드를 실어 상위에서 만료 감지에 쓰게 한다. */
    private static final class DsmException extends Exception {
        final int code;
        DsmException(int code, String msg) { super(msg + " (code=" + code + ")"); this.code = code; }
    }
}
