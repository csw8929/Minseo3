package com.example.minseo3;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 리더에서 선택한 배경/글자 색을 앱 전역에 공유하기 위한 얇은 유틸.
 *
 * 저장 위치는 ReaderFragment 가 쓰던 "reader_prefs" SharedPreferences 그대로 —
 * 별도 복사본 없이 한 곳에서 읽어감. BookListActivity / 자식 Fragment 가 이
 * 값으로 자기 루트를 칠해 전체 화면 배경이 통일되게 한다.
 */
public final class ThemePrefs {

    private static final String PREFS_NAME = "reader_prefs";
    private static final String PREF_TEXT_COLOR = "text_color";
    private static final String PREF_BG_COLOR   = "bg_color";

    private static final int DEFAULT_BG   = 0xFFFFFFFF;
    private static final int DEFAULT_TEXT = 0xFF222222;

    private ThemePrefs() {}

    public static int bgColor(Context c) {
        return prefs(c).getInt(PREF_BG_COLOR, DEFAULT_BG);
    }

    public static int textColor(Context c) {
        return prefs(c).getInt(PREF_TEXT_COLOR, DEFAULT_TEXT);
    }

    /** 배경색의 밝기 판정 — 리스트 항목 divider / 보조 텍스트 색 결정에 사용. */
    public static boolean isDarkBg(Context c) {
        int bg = bgColor(c);
        int r = (bg >> 16) & 0xFF;
        int g = (bg >> 8)  & 0xFF;
        int b =  bg        & 0xFF;
        // 상대 휘도 근사값 (Rec. 709).
        int luma = (r * 299 + g * 587 + b * 114) / 1000;
        return luma < 128;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
