package com.example.minseo3;

import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsBottomSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onChanged(float textSizeSp, int textColor, int bgColor, boolean tapSwap);
    }

    private static final String ARG_SIZE = "size";
    private static final String ARG_TEXT_COLOR = "text_color";
    private static final String ARG_BG_COLOR = "bg_color";
    private static final String ARG_TAP_SWAP = "tap_swap";

    // Background themes: {bgColor, textColor}
    private static final int[][] THEMES = {
            {0xFFFFFFFF, 0xFF222222}, // White
            {0xFFF5ECD7, 0xFF3B2A1A}, // Sepia
            {0xFFF0F0F0, 0xFF222222}, // Light gray
            {0xFF2B2B2B, 0xFFDDDDDD}, // Dark
            {0xFF000000, 0xFFEEEEEE}, // Black
    };

    private static final float[] FONT_SIZES = {14, 16, 17, 18, 20, 22, 24, 28};

    private Listener listener;
    private float currentSizeSp;
    private int currentTextColor;
    private int currentBgColor;
    private boolean currentTapSwap;
    private View[] themeButtons;
    private Drawable selectedRing;

    public static SettingsBottomSheet newInstance(float sizeSp, int textColor, int bgColor, boolean tapSwap) {
        SettingsBottomSheet f = new SettingsBottomSheet();
        Bundle args = new Bundle();
        args.putFloat(ARG_SIZE, sizeSp);
        args.putInt(ARG_TEXT_COLOR, textColor);
        args.putInt(ARG_BG_COLOR, bgColor);
        args.putBoolean(ARG_TAP_SWAP, tapSwap);
        f.setArguments(args);
        return f;
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 가로 모드 / 작은 높이에서 bottom sheet 가 peek 상태로 뜨며 아래 내용이 잘리는
     *  문제 — 시트 표시 직후 BottomSheetBehavior 를 EXPANDED 로 강제. 더불어 content
     *  을 NestedScrollView 로 감싸 높이가 넘치면 스크롤 가능. */
    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (!(dialog instanceof BottomSheetDialog)) return;
        FrameLayout sheet = ((BottomSheetDialog) dialog).findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottom_sheet_settings, container, false);

        Bundle args = getArguments();
        currentSizeSp = args != null ? args.getFloat(ARG_SIZE, 17f) : 17f;
        currentTextColor = args != null ? args.getInt(ARG_TEXT_COLOR, 0xFF222222) : 0xFF222222;
        currentBgColor = args != null ? args.getInt(ARG_BG_COLOR, 0xFFFFFFFF) : 0xFFFFFFFF;
        currentTapSwap = args != null && args.getBoolean(ARG_TAP_SWAP, false);

        // Font size seek bar
        SeekBar seekSize = v.findViewById(R.id.seek_font_size);
        TextView tvSize = v.findViewById(R.id.tv_font_size);
        seekSize.setMax(FONT_SIZES.length - 1);
        int sizeIdx = closestFontSizeIndex(currentSizeSp);
        seekSize.setProgress(sizeIdx);
        tvSize.setText(String.valueOf((int) FONT_SIZES[sizeIdx]));
        seekSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                currentSizeSp = FONT_SIZES[progress];
                tvSize.setText(String.valueOf((int) currentSizeSp));
                notifyListener();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Background theme buttons
        int[] themeButtonIds = {
                R.id.btn_theme_white, R.id.btn_theme_sepia,
                R.id.btn_theme_gray, R.id.btn_theme_dark, R.id.btn_theme_black
        };
        selectedRing = ContextCompat.getDrawable(requireContext(), R.drawable.theme_circle_selected_ring);
        themeButtons = new View[themeButtonIds.length];
        for (int i = 0; i < themeButtonIds.length; i++) {
            final int idx = i;
            View btn = v.findViewById(themeButtonIds[i]);
            themeButtons[i] = btn;
            if (btn != null) btn.setOnClickListener(view -> {
                currentBgColor = THEMES[idx][0];
                currentTextColor = THEMES[idx][1];
                updateSelectedThemeIndicator();
                notifyListener();
            });
        }
        updateSelectedThemeIndicator();

        // Tap swap switch
        MaterialSwitch switchTapSwap = v.findViewById(R.id.switch_tap_swap);
        if (switchTapSwap != null) {
            switchTapSwap.setChecked(currentTapSwap);
            switchTapSwap.setOnCheckedChangeListener((btn, checked) -> {
                currentTapSwap = checked;
                notifyListener();
            });
        }

        return v;
    }

    private void updateSelectedThemeIndicator() {
        if (themeButtons == null) return;
        for (int i = 0; i < themeButtons.length; i++) {
            View btn = themeButtons[i];
            if (btn == null) continue;
            boolean selected = THEMES[i][0] == currentBgColor;
            btn.setForeground(selected ? selectedRing : null);
        }
    }

    private void notifyListener() {
        if (listener != null) listener.onChanged(currentSizeSp, currentTextColor, currentBgColor, currentTapSwap);
    }

    private int closestFontSizeIndex(float sp) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < FONT_SIZES.length; i++) {
            float diff = Math.abs(FONT_SIZES[i] - sp);
            if (diff < bestDiff) { bestDiff = diff; best = i; }
        }
        return best;
    }
}
