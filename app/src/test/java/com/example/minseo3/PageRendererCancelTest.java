package com.example.minseo3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PageRenderer#paginate} 의 취소 / progress 콜백 동작 검증.
 *
 * StaticLayout 은 Android 런타임 클래스라 JVM 단위 테스트에서 실제 레이아웃은 못 함.
 * 대신 취소 flag 가 pre-paginate 경로를 건드리지 않는지 + 비정상 입력 조기 반환만 확인.
 */
public class PageRendererCancelTest {

    @Test public void cancelledFlag_setBeforeCall_returnsEmpty() {
        PageRenderer pr = new PageRenderer();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        // 빈 텍스트는 StaticLayout 건드리기 전에 바로 반환 — cancel 여부와 무관하게 [0] 반환.
        List<Integer> offsets = pr.paginate("", 16f, 100, 100, cancelled, null);
        assertEquals(1, offsets.size());
        assertEquals(Integer.valueOf(0), offsets.get(0));
    }

    @Test public void emptyText_noProgressCallback() {
        PageRenderer pr = new PageRenderer();
        AtomicInteger callCount = new AtomicInteger(0);
        pr.paginate("", 16f, 100, 100, null, pct -> callCount.incrementAndGet());
        // 루프 진입 자체를 안 하므로 progress 콜백 0 회.
        assertEquals(0, callCount.get());
    }

    @Test public void zeroWidth_returnsEmpty() {
        PageRenderer pr = new PageRenderer();
        List<Integer> offsets = pr.paginate("some text", 16f, 0, 100, null, null);
        assertEquals(1, offsets.size());
    }

    @Test public void legacySignature_stillCompiles() {
        // 기존 시그니처 (cancel/progress 없음) 가 오버로드로 보존됐는지.
        PageRenderer pr = new PageRenderer();
        List<Integer> offsets = pr.paginate("", 16f, 100, 100);
        assertTrue(offsets.size() >= 1);
    }
}
