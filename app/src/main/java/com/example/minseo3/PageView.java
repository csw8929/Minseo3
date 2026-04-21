package com.example.minseo3;

import android.content.Context;
import android.graphics.Canvas;
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
