package com.example.minseo3;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_CHAR_OFFSET = "char_offset";

    // ── UI
    private PageView pageView;
    private TextView tvPageInfo;
    private TextView tvStatusLeft;
    private SeekBar seekBar;
    private View topBar, bottomBar;
    private ImageButton btnSettings, btnTts;

    // ── State
    private String text = "";
    private String fileHash = "";
    private String filePath = "";
    private int currentPage = 0;
    private boolean uiVisible = true;

    // ── Settings (persisted in SharedPreferences "reader_prefs")
    private static final String PREFS_NAME = "reader_prefs";
    private static final String PREF_TEXT_SIZE_SP = "text_size_sp";
    private static final String PREF_TEXT_COLOR   = "text_color";
    private static final String PREF_BG_COLOR     = "bg_color";

    private SharedPreferences readerPrefs;
    private float textSizeSp = 17f;
    private int textColor = 0xFF222222;
    private int bgColor = 0xFFFFFFFF;

    // ── Engine
    private final PageRenderer pageRenderer = new PageRenderer();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Save: 5-second debounce after last page turn
    private LocalProgressRepository progressRepo;
    private NasSyncManager nasSyncManager;
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = () -> executor.execute(() -> {
        int offset = pageRenderer.getPageStartOffset(currentPage);
        progressRepo.save(fileHash, filePath, offset, text.length());
        nasSyncManager.push(fileHash, filePath, offset, text.length());
    });

    private TtsController tts;
    private boolean ttsActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterFullscreen();
        setContentView(R.layout.activity_reader);

        pageView      = findViewById(R.id.page_view);
        tvPageInfo    = findViewById(R.id.tv_page_info);
        tvStatusLeft  = findViewById(R.id.tv_status_left);
        seekBar       = findViewById(R.id.seek_bar);
        topBar        = findViewById(R.id.top_bar);
        bottomBar     = findViewById(R.id.bottom_bar);
        btnSettings   = findViewById(R.id.btn_settings);
        btnTts        = findViewById(R.id.btn_tts);

        progressRepo = new LocalProgressRepository(this);
        nasSyncManager = new NasSyncManager(this);

        readerPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        textSizeSp = readerPrefs.getFloat(PREF_TEXT_SIZE_SP, textSizeSp);
        textColor  = readerPrefs.getInt(PREF_TEXT_COLOR, textColor);
        bgColor    = readerPrefs.getInt(PREF_BG_COLOR, bgColor);

        tts = new TtsController(this, new TtsController.Listener() {
            @Override public void onReady() {}
            @Override public void onDone() {
                if (ttsActive) mainHandler.post(() -> nextPage());
            }
            @Override public void onError() {
                mainHandler.post(() -> { ttsActive = false; updateTtsButton(); });
            }
        });

        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        int startOffset = getIntent().getIntExtra(EXTRA_CHAR_OFFSET, 0);

        if (filePath == null) { finish(); return; }

        File file = new File(filePath);
        fileHash = FileUtils.computeHash(file);
        setTitle(FileUtils.displayName(file));

        setupTouchHandler();
        setupSeekBar();
        setupTopMenu();
        btnSettings.setOnClickListener(v -> showSettings());
        btnTts.setOnClickListener(v -> toggleTts());

        // Back button = exit app, not return to list
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                finishAffinity();
            }
        });

        loadFile(file, startOffset);
    }

    private void enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ── Top menu (리스트 / 설정 / NAS) ────────────────────────────────────────

    private void setupTopMenu() {
        findViewById(R.id.menu_list).setOnClickListener(v -> {
            // Bring the existing BookList forward, keeping Reader in the stack
            // so that pressing back on BookList returns to this novel.
            Intent intent = new Intent(this, BookListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });
        findViewById(R.id.menu_settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.menu_nas).setOnClickListener(v ->
                startActivity(new Intent(this, NasSettingsActivity.class)));
    }

    // ── File loading + pagination ────────────────────────────────────────────

    private void loadFile(File file, int startOffset) {
        showLoading(true);
        executor.execute(() -> {
            try {
                String loaded = FileUtils.readTextFile(file);
                mainHandler.post(() -> {
                    text = loaded;
                    pageView.post(() -> paginate(startOffset));
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    showLoading(false);
                    Toast.makeText(this, "파일을 읽을 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private void paginate(int startOffset) {
        int w = pageView.getWidth() - pageView.getPaddingLeft() - pageView.getPaddingRight();
        int h = pageView.getHeight() - pageView.getPaddingTop() - pageView.getPaddingBottom();
        float textSizePx = spToPx(textSizeSp);

        executor.execute(() -> {
            pageRenderer.paginate(text, textSizePx, w, h);
            int page = pageRenderer.offsetToPage(startOffset);
            mainHandler.post(() -> {
                showLoading(false);
                seekBar.setMax(Math.max(1, pageRenderer.getPageCount() - 1));
                displayPage(page);
            });
        });
    }

    // ── Page display ─────────────────────────────────────────────────────────

    private void displayPage(int page) {
        if (page < 0) page = 0;
        if (page >= pageRenderer.getPageCount()) page = pageRenderer.getPageCount() - 1;
        currentPage = page;

        pageView.setPage(pageRenderer.getPageText(text, currentPage), spToPx(textSizeSp), textColor, bgColor);

        int total = pageRenderer.getPageCount();
        int percent = (total > 0) ? ((currentPage + 1) * 100 / total) : 0;
        String pageStr = (currentPage + 1) + " / " + total;
        tvPageInfo.setText(pageStr);
        tvStatusLeft.setText(pageStr + "  (" + percent + "%)");
        seekBar.setProgress(currentPage);

        scheduleSave();
    }

    private void nextPage() {
        if (currentPage < pageRenderer.getPageCount() - 1) displayPage(currentPage + 1);
    }

    // ── Page-turn tap coalescing ─────────────────────────────────────────────
    // Rapid taps accumulate into a single final jump so we don't waste
    // StaticLayout rebuilds on intermediate pages.
    private int pendingPageDelta = 0;
    private final Runnable applyPageDeltaRunnable = () -> {
        if (pendingPageDelta == 0) return;
        int target = currentPage + pendingPageDelta;
        pendingPageDelta = 0;
        displayPage(target);
        if (ttsActive) speakCurrentPage();
    };

    private void requestPageMove(int delta) {
        pendingPageDelta += delta;
        mainHandler.removeCallbacks(applyPageDeltaRunnable);
        mainHandler.postDelayed(applyPageDeltaRunnable, 60);
    }

    // ── Save: 5-second debounce ───────────────────────────────────────────────

    private void scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable);
        saveHandler.postDelayed(saveRunnable, 5000);
    }

    /**
     * Save synchronously on the calling thread. Used in onPause so that
     * BookListActivity.onResume() reads the just-updated progress instead of
     * racing with the executor's background save.
     */
    private void flushSaveNow() {
        saveHandler.removeCallbacks(saveRunnable);
        if (text.isEmpty() || pageRenderer.getPageCount() == 0) return;
        int offset = pageRenderer.getPageStartOffset(currentPage);
        progressRepo.save(fileHash, filePath, offset, text.length());
        nasSyncManager.push(fileHash, filePath, offset, text.length());
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    private void setupTouchHandler() {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            // onSingleTapUp (not onSingleTapConfirmed) so taps fire immediately
            // without waiting for the double-tap timeout — fast consecutive taps
            // were being swallowed and felt like dropped frames.
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX();
                float w = pageView.getWidth();
                if (x < w / 3f) {
                    requestPageMove(-1);
                } else if (x > 2f * w / 3f) {
                    requestPageMove(+1);
                } else {
                    toggleUIBars();
                }
                return true;
            }
        });
        pageView.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
    }

    private void setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) displayPage(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // ── UI toggle ────────────────────────────────────────────────────────────

    private void toggleUIBars() {
        uiVisible = !uiVisible;
        int vis = uiVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(vis);
        bottomBar.setVisibility(vis);
    }

    private void showLoading(boolean show) {
        View loading = findViewById(R.id.loading);
        if (loading != null) loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    private void showSettings() {
        SettingsBottomSheet sheet = SettingsBottomSheet.newInstance(textSizeSp, textColor, bgColor);
        sheet.setListener((newSizeSp, newTextColor, newBgColor) -> {
            boolean sizeChanged = newSizeSp != textSizeSp;
            textSizeSp = newSizeSp;
            textColor = newTextColor;
            bgColor = newBgColor;
            readerPrefs.edit()
                    .putFloat(PREF_TEXT_SIZE_SP, textSizeSp)
                    .putInt(PREF_TEXT_COLOR, textColor)
                    .putInt(PREF_BG_COLOR, bgColor)
                    .apply();
            if (sizeChanged) {
                int currentOffset = pageRenderer.getPageStartOffset(currentPage);
                showLoading(true);
                pageView.post(() -> paginate(currentOffset));
            } else {
                pageView.setPage(pageRenderer.getPageText(text, currentPage), spToPx(textSizeSp), textColor, bgColor);
                pageView.setColors(textColor, bgColor);
            }
        });
        sheet.show(getSupportFragmentManager(), "settings");
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    private void toggleTts() {
        ttsActive = !ttsActive;
        updateTtsButton();
        if (ttsActive) speakCurrentPage(); else tts.stop();
    }

    private void speakCurrentPage() {
        tts.speak(pageRenderer.getPageText(text, currentPage));
    }

    private void updateTtsButton() {
        btnTts.setImageResource(ttsActive ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        tts.stop();
        ttsActive = false;
        flushSaveNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tts.shutdown();
        executor.shutdown();
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }
}
