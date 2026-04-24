package com.example.minseo3.nas;

import android.util.Log;

import com.example.minseo3.Bookmark;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.example.minseo3.nas.SynologyDsmHelper.ERROR_NO_SUCH_FILE;
import static com.example.minseo3.nas.SynologyDsmHelper.errorCode;
import static com.example.minseo3.nas.SynologyDsmHelper.normalizeDir;
import static com.example.minseo3.nas.SynologyDsmHelper.withSidAndRetry;

/**
 * Synology FileStation 에 bm_{fileHash}.json 을 읽고 쓰는 {@link RemoteBookmarksRepository} 구현.
 *
 * 파일 포맷:
 * <pre>
 * {
 *   "version": 1,
 *   "fileHash": "…",
 *   "bookmarks": [ {Bookmark}, … ]   // alive + tombstone 모두 포함
 * }
 * </pre>
 *
 * 정책 (Lenient):
 * - 파일 없음 (HTML 404 / DSM 408) → 빈 리스트
 * - 파싱 실패 → 빈 리스트 + warning log
 * - 미래 버전 (version > 1) → 알 수 있는 필드만 읽음
 */
public final class SynologyBookmarksRepository implements RemoteBookmarksRepository {

    private static final String TAG = "NasSync";
    private static final int SCHEMA_VERSION = 1;
    private static final String FILE_PREFIX = "bm_";
    private static final String FILE_SUFFIX = ".json";

    public SynologyBookmarksRepository() {}

    private static String resolveDir() { return normalizeDir(DsAuth.cfgPosDir); }

    private static String fileName(String fileHash) {
        return FILE_PREFIX + fileHash + FILE_SUFFIX;
    }

    // ── push ────────────────────────────────────────────────────────────────

    @Override
    public void push(String fileHash, List<Bookmark> bookmarks, Callback<Void> cb) {
        String dir = resolveDir();
        String name = fileName(fileHash);
        int count = bookmarks == null ? 0 : bookmarks.size();
        try {
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            root.put("fileHash", fileHash);
            JSONArray arr = new JSONArray();
            for (Bookmark b : bookmarks) arr.put(b.toJson());
            root.put("bookmarks", arr);
            byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);

            Log.i(TAG, "SACH http upload bm: " + dir + "/" + name
                    + " count=" + count + " (" + body.length + " bytes)");
            withSidAndRetry(sid -> {
                String url = DsAuth.apiBase() + "/webapi/entry.cgi?_sid="
                        + URLEncoder.encode(sid, "UTF-8");
                String resp = DsHttp.uploadFile(url, dir, name, body, sid);
                JSONObject json = new JSONObject(resp);
                if (!json.optBoolean("success", false)) {
                    int code = errorCode(json);
                    throw new SynologyDsmHelper.DsmException(code, "bm upload failed");
                }
                return null;
            });
            Log.i(TAG, "SACH http upload bm ok: " + dir + "/" + name);
            cb.onResult(null);
        } catch (Exception e) {
            Log.w(TAG, "SACH http upload bm failed: " + dir + "/" + name
                    + " msg=" + e.getMessage());
            cb.onError(e.getMessage());
        }
    }

    // ── fetchOne ────────────────────────────────────────────────────────────

    @Override
    public void fetchOne(String fileHash, Callback<List<Bookmark>> cb) {
        String path = resolveDir() + "/" + fileName(fileHash);
        Log.i(TAG, "SACH http download bm: " + path);
        try {
            List<Bookmark> list = withSidAndRetry(sid -> {
                String url = DsAuth.apiBase() + "/webapi/entry.cgi"
                        + "?api=SYNO.FileStation.Download&version=2&method=download"
                        + "&path=" + URLEncoder.encode(path, "UTF-8")
                        + "&mode=open"
                        + "&_sid=" + sid;
                String body = DsHttp.httpGet(url);
                // HTML 404
                if (body.startsWith("<")) return Collections.<Bookmark>emptyList();
                // DSM envelope (예: 408 파일 없음)
                if (body.startsWith("{") && body.contains("\"success\"")) {
                    JSONObject envelope = new JSONObject(body);
                    if (!envelope.optBoolean("success", false)) {
                        int code = errorCode(envelope);
                        if (code == ERROR_NO_SUCH_FILE) return Collections.<Bookmark>emptyList();
                        throw new SynologyDsmHelper.DsmException(code, "bm download failed");
                    }
                }
                try {
                    JSONObject root = new JSONObject(body);
                    JSONArray arr = root.optJSONArray("bookmarks");
                    if (arr == null) return Collections.<Bookmark>emptyList();
                    List<Bookmark> out = new ArrayList<>(arr.length());
                    for (int i = 0; i < arr.length(); i++) {
                        try {
                            out.add(Bookmark.fromJson(arr.getJSONObject(i)));
                        } catch (JSONException ignored) {
                            // skip unparseable records, keep the rest (lenient)
                        }
                    }
                    return out;
                } catch (JSONException parseErr) {
                    Log.w(TAG, "bm parse failed: " + parseErr.getMessage());
                    return Collections.<Bookmark>emptyList();
                }
            });
            Log.i(TAG, "SACH http download bm done: " + path + " count=" + list.size());
            cb.onResult(list);
        } catch (Exception e) {
            Log.w(TAG, "SACH http download bm failed: " + path + " msg=" + e.getMessage());
            cb.onError(e.getMessage());
        }
    }
}
