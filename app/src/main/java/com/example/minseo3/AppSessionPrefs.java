package com.example.minseo3;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 앱 세션 상태 (런치 결정에 쓰이는 미니 프리퍼런스) — 마지막 종료 시점의 탭 위치.
 *
 * 저장소는 ThemePrefs 와 같은 "reader_prefs" SharedPreferences 공유 — 별도 파일
 * 만들 만큼의 스키마가 아니고 앱 수명 주기 내내 읽고 쓰는 1 개 키.
 *
 * 값: "reader" | "list".
 *   "reader" = 마지막에 리더 탭(position 2) 에 있었다.
 *   "list"   = 그 외 (내 책 / 즐겨찾기). 기본값.
 *
 * 런치 시 Local 이 NAS 보다 최신이면 이 값으로 auto-reader vs stay-on-list 분기.
 */
public final class AppSessionPrefs {

    private static final String PREFS_NAME = "reader_prefs";
    private static final String KEY_LAST_EXIT_MODE = "last_exit_mode";

    public static final String MODE_READER = "reader";
    public static final String MODE_LIST   = "list";

    private AppSessionPrefs() {}

    public static String getLastExitMode(Context c) {
        return prefs(c).getString(KEY_LAST_EXIT_MODE, MODE_LIST);
    }

    public static void setLastExitMode(Context c, String mode) {
        prefs(c).edit().putString(KEY_LAST_EXIT_MODE, mode).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
