package com.example.minseo3;

import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Locale;

public class SettingsBottomSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onChanged(float textSizeSp, int textColor, int bgColor, boolean tapSwap, boolean bold);

        /**
         * 글자 크기 슬라이더 드래그 중 실시간 프리뷰. 기본 구현은 no-op.
         * 드래그 release 시점에만 {@link #onChanged} 가 한 번 호출되어 full paginate.
         */
        default void onSizePreview(float textSizeSp) {}

        /** TTS 읽기 속도 변경 (즉시 적용). */
        default void onTtsRateChanged(float rate) {}

        /** "음성 엔진 변경" 버튼 — 시스템 TTS 설정 화면 열기. */
        default void onOpenTtsEngineSettings() {}
    }

    private static final String ARG_SIZE = "size";
    private static final String ARG_TEXT_COLOR = "text_color";
    private static final String ARG_BG_COLOR = "bg_color";
    private static final String ARG_TAP_SWAP = "tap_swap";
    private static final String ARG_BOLD = "bold";
    private static final String ARG_TTS_RATE = "tts_rate";

    /** TTS 속도 매핑: progress (0..15) → rate 0.5x..2.0x, 0.1 단위. */
    private static final int TTS_RATE_STEPS = 15;
    private static final float TTS_RATE_MIN = 0.5f;
    private static final float TTS_RATE_STEP = 0.1f;

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
    private boolean currentBold;
    private float currentTtsRate;
    private View[] themeButtons;
    private Drawable selectedRing;
    /** 글자 크기 슬라이더가 터치 드래그 중인지. onStartTrackingTouch / onStopTrackingTouch 로 토글. */
    private boolean sizeSliderDragging = false;
    /** TTS 속도 슬라이더 드래그 중인지. 매 notch 마다 재발화 폭주 방지 — release 시점에만 commit. */
    private boolean rateSliderDragging = false;

    public static SettingsBottomSheet newInstance(float sizeSp, int textColor, int bgColor,
                                                  boolean tapSwap, boolean bold, float ttsRate) {
        SettingsBottomSheet f = new SettingsBottomSheet();
        Bundle args = new Bundle();
        args.putFloat(ARG_SIZE, sizeSp);
        args.putInt(ARG_TEXT_COLOR, textColor);
        args.putInt(ARG_BG_COLOR, bgColor);
        args.putBoolean(ARG_TAP_SWAP, tapSwap);
        args.putBoolean(ARG_BOLD, bold);
        args.putFloat(ARG_TTS_RATE, ttsRate);
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
        currentBold = args != null && args.getBoolean(ARG_BOLD, false);
        currentTtsRate = args != null ? args.getFloat(ARG_TTS_RATE, 1.0f) : 1.0f;

        // Font size seek bar
        SeekBar seekSize = v.findViewById(R.id.seek_font_size);
        TextView tvSize = v.findViewById(R.id.tv_font_size);
        seekSize.setMax(FONT_SIZES.length - 1);
        int sizeIdx = closestFontSizeIndex(currentSizeSp);
        seekSize.setProgress(sizeIdx);
        tvSize.setText(String.valueOf((int) FONT_SIZES[sizeIdx]));
        // 드래그 중엔 listener.onSizePreview (싼 rescale only), release 시에만 onChanged (full paginate).
        // 큰 파일에서 드래그 한 번에 8단계를 휙휙 넘길 때 각 단계마다 paginate 가 돌면
        // 취소/재시작이 폭주해서 체감 여전히 느려짐 — commit-on-release 로 한 번만 돌게.
        seekSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                currentSizeSp = FONT_SIZES[progress];
                tvSize.setText(String.valueOf((int) currentSizeSp));
                if (sizeSliderDragging && fromUser) {
                    if (listener != null) listener.onSizePreview(currentSizeSp);
                } else {
                    // 접근성 등 non-touch 경로 (talkback ACTION_SET_PROGRESS 등) 는 바로 commit.
                    notifyListener();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { sizeSliderDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                sizeSliderDragging = false;
                notifyListener(); // 드래그 release — 최종 크기로 commit.
            }
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

        // Bold switch
        MaterialSwitch switchBold = v.findViewById(R.id.switch_bold);
        if (switchBold != null) {
            switchBold.setChecked(currentBold);
            switchBold.setOnCheckedChangeListener((btn, checked) -> {
                currentBold = checked;
                notifyListener();
            });
        }

        // TTS 속도 — release 시점에만 commit. 드래그 중 매 notch 마다 service 재발화하면 음성이
        // 0.1x 단계로 끊겨 들림. 비-터치 (a11y SET_PROGRESS) 는 즉시 commit.
        SeekBar seekRate = v.findViewById(R.id.seek_tts_rate);
        TextView tvRate = v.findViewById(R.id.tv_tts_rate);
        if (seekRate != null && tvRate != null) {
            seekRate.setMax(TTS_RATE_STEPS);
            int rateIdx = ttsRateToProgress(currentTtsRate);
            seekRate.setProgress(rateIdx);
            tvRate.setText(formatRate(currentTtsRate));
            seekRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    currentTtsRate = progressToTtsRate(progress);
                    tvRate.setText(formatRate(currentTtsRate));
                    if (rateSliderDragging && fromUser) {
                        // 드래그 중 — 화면 표시만 갱신.
                    } else if (listener != null) {
                        // 비-터치 (a11y) — 즉시 commit.
                        listener.onTtsRateChanged(currentTtsRate);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { rateSliderDragging = true; }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    rateSliderDragging = false;
                    if (listener != null) listener.onTtsRateChanged(currentTtsRate);
                }
            });
        }

        // 음성 엔진 변경 — 시스템 TTS 설정 화면 호출 (Fragment 가 멀티 폴백 처리).
        Button btnEngine = v.findViewById(R.id.btn_tts_engine_settings);
        if (btnEngine != null) {
            btnEngine.setOnClickListener(view -> {
                if (listener != null) listener.onOpenTtsEngineSettings();
            });
        }

        return v;
    }

    private static int ttsRateToProgress(float rate) {
        int idx = Math.round((rate - TTS_RATE_MIN) / TTS_RATE_STEP);
        if (idx < 0) idx = 0;
        if (idx > TTS_RATE_STEPS) idx = TTS_RATE_STEPS;
        return idx;
    }

    private static float progressToTtsRate(int progress) {
        float r = TTS_RATE_MIN + progress * TTS_RATE_STEP;
        // float 누적 오차 방어 — 0.1 단위로 반올림.
        return Math.round(r * 10f) / 10f;
    }

    private static String formatRate(float rate) {
        return String.format(Locale.US, "%.1fx", rate);
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
        if (listener != null) listener.onChanged(currentSizeSp, currentTextColor, currentBgColor, currentTapSwap, currentBold);
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
