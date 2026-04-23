package com.example.minseo3.nas;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.example.minseo3.nas.SynologyDsmHelper.ERROR_NO_SUCH_FILE;
import static com.example.minseo3.nas.SynologyDsmHelper.errorCode;
import static com.example.minseo3.nas.SynologyDsmHelper.normalizeDir;
import static com.example.minseo3.nas.SynologyDsmHelper.withSidAndRetry;

/**
 * Synology FileStation 에 pos_*.json 을 읽고 쓰는 RemoteProgressRepository 구현.
 *
 * 모든 메서드는 호출 스레드에서 동기 실행 — {@code NasSyncManager.networkExecutor}가
 * 백그라운드로 감싸는 것을 전제로 한다.
 *
 * 주의: {@link DsAuth#init} 이 이미 호출된 상태여야 한다.
 *
 * SID 재시도 / 에러 코드 / 경로 정규화는 {@link SynologyDsmHelper} 로 추출되어
 * {@link SynologyBookmarksRepository} 와 공유한다.
 */
public final class SynologyFileStationRepository implements RemoteProgressRepository {

    private static final String TAG = "NasSync";

    private final String deviceId;

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
                // Upload 엔드포인트는 _sid 를 URL 쿼리로도 받아야 함 — form field 만 전달하면
                // DSM 이 code 119 (SID not found) 로 거부함. Minseo21 DsPlayback 주석 참고.
                String url = DsAuth.apiBase() + "/webapi/entry.cgi?_sid="
                        + URLEncoder.encode(sid, "UTF-8");
                String resp = DsHttp.uploadFile(url, resolvePosDir(),
                        "pos_" + fileHash + ".json", body, sid);
                JSONObject json = new JSONObject(resp);
                if (!json.optBoolean("success", false)) {
                    int code = errorCode(json);
                    throw new SynologyDsmHelper.DsmException(code, "upload failed");
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
                // HTTP 404 는 HTML 에러 페이지로 옴 → "파일 없음"으로 간주.
                if (body.startsWith("<")) return null;
                // 파일이 없거나 권한 오류면 {success:false, error:{code:...}} envelope 리턴.
                if (body.startsWith("{") && body.contains("\"success\"")) {
                    JSONObject envelope = new JSONObject(body);
                    if (!envelope.optBoolean("success", false)) {
                        int code = errorCode(envelope);
                        if (code == ERROR_NO_SUCH_FILE) return null;
                        throw new SynologyDsmHelper.DsmException(code, "download failed");
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
                    throw new SynologyDsmHelper.DsmException(code, "list failed");
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

    // ── delete ──────────────────────────────────────────────────────────────

    @Override
    public void delete(String fileHash, Callback<Void> cb) {
        String path = resolvePosDir() + "/pos_" + fileHash + ".json";
        try {
            withSidAndRetry(sid -> {
                String url = DsAuth.apiBase() + "/webapi/entry.cgi"
                        + "?api=SYNO.FileStation.Delete&version=2&method=start"
                        + "&path=" + URLEncoder.encode(path, "UTF-8")
                        + "&accurate_progress=false&recursive=false"
                        + "&_sid=" + sid;
                String body = DsHttp.httpGet(url);
                // HTML 404 또는 SUCCESS — 파일이 없어도 멱등 성공 취급.
                if (body.startsWith("<")) return null;
                JSONObject envelope = new JSONObject(body);
                if (!envelope.optBoolean("success", false)) {
                    int code = errorCode(envelope);
                    if (code == ERROR_NO_SUCH_FILE) return null; // 이미 없음 — OK
                    throw new SynologyDsmHelper.DsmException(code, "delete failed");
                }
                return null;
            });
            cb.onResult(null);
        } catch (Exception e) {
            Log.w(TAG, "delete failed (" + fileHash + "): " + e.getMessage());
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
        if (body.startsWith("<")) return null; // HTML 404 → 없음
        if (body.startsWith("{") && body.contains("\"success\"")) {
            JSONObject envelope = new JSONObject(body);
            if (!envelope.optBoolean("success", false)) return null;
        }
        return RemotePosition.fromJson(new JSONObject(body));
    }
}
