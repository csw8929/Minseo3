package com.example.minseo3.nas;

public final class NasSyncConstants {
    private NasSyncConstants() {}

    /** 같은 세션으로 간주할 lastUpdated 차이 상한. 초기값은 실사용 피드백으로 튜닝. */
    public static final long SAME_SESSION_WINDOW_MS = 5L * 60 * 1000;

    /** 같은 세션으로 간주할 charOffset 차이 상한. 한국어 분당 ~300-500자 가정, 1분 이내 읽기량. */
    public static final int SAME_SESSION_OFFSET_DIFF = 500;
}
