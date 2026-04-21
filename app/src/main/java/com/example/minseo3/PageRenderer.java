package com.example.minseo3;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Paginates text into pages using StaticLayout.
 * Position unit is always charOffset (not page number) so cross-device NAS sync works
 * regardless of screen size or font size differences between devices.
 */
public class PageRenderer {

    static final float LINE_SPACING_MULT = 1.3f;
    static final float LINE_SPACING_EXTRA = 0f;

    private List<Integer> pageOffsets = new ArrayList<>();

    /**
     * @param text        full text content
     * @param textSizePx  text size in pixels (convert from sp before calling)
     * @param widthPx     available width (view width minus horizontal padding)
     * @param heightPx    available height (view height minus vertical padding)
     */
    public List<Integer> paginate(String text, float textSizePx, int widthPx, int heightPx) {
        pageOffsets = new ArrayList<>();
        pageOffsets.add(0);

        if (text == null || text.isEmpty() || widthPx <= 0 || heightPx <= 0) {
            return Collections.unmodifiableList(pageOffsets);
        }

        TextPaint paint = new TextPaint();
        paint.setAntiAlias(true);
        paint.setTextSize(textSizePx);

        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(LINE_SPACING_EXTRA, LINE_SPACING_MULT)
                .setIncludePad(false)
                .build();

        float pageTopY = layout.getLineTop(0);

        for (int line = 1; line < layout.getLineCount(); line++) {
            float lineBottom = layout.getLineBottom(line);
            if (lineBottom - pageTopY > heightPx) {
                pageOffsets.add(layout.getLineStart(line));
                pageTopY = layout.getLineTop(line);
            }
        }

        return Collections.unmodifiableList(pageOffsets);
    }

    public CharSequence getPageText(String text, int pageIndex) {
        if (text == null || pageOffsets.isEmpty()) return "";
        if (pageIndex < 0) pageIndex = 0;
        if (pageIndex >= pageOffsets.size()) pageIndex = pageOffsets.size() - 1;
        int start = pageOffsets.get(pageIndex);
        int end = (pageIndex + 1 < pageOffsets.size()) ? pageOffsets.get(pageIndex + 1) : text.length();
        return text.substring(start, Math.min(end, text.length()));
    }

    /** Binary search: largest pageOffset index whose value <= charOffset. */
    public int offsetToPage(int charOffset) {
        if (pageOffsets.isEmpty()) return 0;
        int lo = 0, hi = pageOffsets.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (pageOffsets.get(mid) <= charOffset) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    public int getPageCount() {
        return pageOffsets.size();
    }

    public int getPageStartOffset(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageOffsets.size()) return 0;
        return pageOffsets.get(pageIndex);
    }
}
