package com.example.minseo3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

public class PageView extends View {

    private StaticLayout pageLayout;
    private final TextPaint textPaint = new TextPaint();
    private CharSequence pageText = "";
    private int backgroundColor = 0xFFFFFFFF;

    public PageView(Context context) { super(context); init(); }
    public PageView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(spToPx(17));
        textPaint.setColor(0xFF222222);
    }

    public void setPage(CharSequence text, float textSizePx, int textColor, int bgColor) {
        this.pageText = text != null ? text : "";
        this.backgroundColor = bgColor;
        textPaint.setTextSize(textSizePx);
        textPaint.setColor(textColor);
        rebuildLayout();
        invalidate();
    }

    public void setColors(int textColor, int bgColor) {
        this.backgroundColor = bgColor;
        textPaint.setColor(textColor);
        rebuildLayout();
        invalidate();
    }

    public void setBold(boolean bold) {
        textPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        rebuildLayout();
        invalidate();
    }

    /**
     * 현재 페이지 텍스트 그대로, 폰트 크기만 즉시 변경해서 리드로우.
     * 설정 시트의 글자 크기 슬라이더를 드래그할 때 프리뷰용.
     *
     * 주의: 페이지 경계(pageOffsets) 는 이전 크기 기준이라 실제 내용이 현재 페이지
     * 영역을 넘치거나 남을 수 있음 — preview 용도로만 사용하고, 슬라이더 release
     * 시점에 반드시 paginate 로 commit 해야 한다.
     */
    public void setTextSizePx(float textSizePx) {
        textPaint.setTextSize(textSizePx);
        rebuildLayout();
        invalidate();
    }

    private void rebuildLayout() {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        if (w <= 0 || pageText.length() == 0) {
            pageLayout = null;
            return;
        }
        pageLayout = StaticLayout.Builder
                .obtain(pageText, 0, pageText.length(), textPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(PageRenderer.LINE_SPACING_EXTRA, PageRenderer.LINE_SPACING_MULT)
                .setIncludePad(false)
                .build();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        rebuildLayout();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(backgroundColor);
        if (pageLayout == null) return;
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        pageLayout.draw(canvas);
        canvas.restore();
    }

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }
}
