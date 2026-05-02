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
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;


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

    /** TTS 속도 — 4개 고정값. 자유 슬라이더보다 결정 비용 낮음. */
    private static final float[] TTS_RATES = {0.7f, 1.0f, 1.2f, 1.5f};

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

        // TTS 속도 — 4개 고정 토글 버튼. 클릭 즉시 commit (단일 액션이라 폭주 없음).
        // 저장된 값이 4개 중 하나가 아니면 (옛 슬라이더 시절 prefs) 가장 가까운 값으로 스냅.
        MaterialButtonToggleGroup toggleRate = v.findViewById(R.id.toggle_tts_rate);
        if (toggleRate != null) {
            int snapIdx = nearestRateIndex(currentTtsRate);
            currentTtsRate = TTS_RATES[snapIdx];
            int[] btnIds = {R.id.btn_rate_07, R.id.btn_rate_10, R.id.btn_rate_12, R.id.btn_rate_15};
            toggleRate.check(btnIds[snapIdx]);
            toggleRate.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                for (int i = 0; i < btnIds.length; i++) {
                    if (btnIds[i] == checkedId) {
                        currentTtsRate = TTS_RATES[i];
                        if (listener != null) listener.onTtsRateChanged(currentTtsRate);
                        break;
                    }
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

    private static int nearestRateIndex(float rate) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < TTS_RATES.length; i++) {
            float d = Math.abs(TTS_RATES[i] - rate);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
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
