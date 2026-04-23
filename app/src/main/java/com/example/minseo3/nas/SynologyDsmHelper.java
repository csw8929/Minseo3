package com.example.minseo3.nas;

import android.util.Log;

import org.json.JSONObject;

/**
 * DSM FileStation 공용 HTTP 헬퍼 — SID 만료 자동 재시도, 에러 코드 파싱,
 * 경로 정규화, envelope 처리. {@link SynologyFileStationRepository} (pos) 와
 * {@link SynologyBookmarksRepository} (bookmark) 가 공유한다.
 *
 * 호출 스레드에서 동기 실행. NasSyncManager.networkExecutor 가 감싸는 것을 전제.
 */
public final class SynologyDsmHelper {

    public static final String TAG = "NasSync";

    /** SID 만료/무효로 간주해 reLogin 후 1회 재시도하는 DSM 에러 코드. */
    private static final int[] SID_EXPIRY_CODES = {105, 106, 107, 119};

    /** FileStation.List / Download 가 대상 부재 시 내려주는 에러 코드. */
    public static final int ERROR_NO_SUCH_FILE = 408;

    private SynologyDsmHelper() {}

    /** 호출자가 SID 를 받아 실제 HTTP 작업을 수행. */
    public interface SidCall<T> {
        T call(String sid) throws Exception;
    }

    /**
     * SID 확보 → 작업 실행 → SID 만료 감지 시 reLogin 후 1회 재시도.
     * DsmException 을 던지면 code 로 만료 여부 판단.
     */
    public static <T> T withSidAndRetry(SidCall<T> call) throws Exception {
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

    public static boolean isSidExpiry(int code) {
        for (int c : SID_EXPIRY_CODES) if (c == code) return true;
        return false;
    }

    public static int errorCode(JSONObject envelope) {
        JSONObject err = envelope.optJSONObject("error");
        return err == null ? -1 : err.optInt("code", -1);
    }

    /** trailing slash 제거 (단, 루트 "/"는 유지), null/empty → "/". */
    public static String normalizeDir(String dir) {
        if (dir == null || dir.isEmpty()) return "/";
        String d = dir.trim();
        if (d.endsWith("/") && d.length() > 1) d = d.substring(0, d.length() - 1);
        return d;
    }

    /** DSM envelope 의 에러 코드를 실어 상위에서 만료 감지에 쓰게 한다. */
    public static final class DsmException extends Exception {
        public final int code;
        public DsmException(int code, String msg) {
            super(msg + " (code=" + code + ")");
            this.code = code;
        }
    }
}
