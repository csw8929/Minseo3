package com.example.minseo3;

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

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_CHAR_OFFSET = "char_offset";

    // ── UI
    private PageView pageView;
    private TextView tvPageInfo;
    private SeekBar seekBar;
    private View bottomBar;
    private ImageButton btnSettings, btnTts;

    // ── State
    private String text = "";
    private String fileHash = "";
    private String filePath = "";
    private int currentPage = 0;
    private boolean uiVisible = true;

    // ── Settings
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
        setContentView(R.layout.activity_reader);

        pageView = findViewById(R.id.page_view);
        tvPageInfo = findViewById(R.id.tv_page_info);
        seekBar = findViewById(R.id.seek_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        btnSettings = findViewById(R.id.btn_settings);
        btnTts = findViewById(R.id.btn_tts);

        progressRepo = new LocalProgressRepository(this);
        nasSyncManager = new NasSyncManager(this);
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
        btnSettings.setOnClickListener(v -> showSettings());
        btnTts.setOnClickListener(v -> toggleTts());

        loadFile(file, startOffset);
    }

    // ── File loading + pagination ────────────────────────────────────────────

    private void loadFile(File file, int startOffset) {
        showLoading(true);
        executor.execute(() -> {
            try {
                String loaded = FileUtils.readTextFile(file);
                mainHandler.post(() -> {
                    text = loaded;
                    // Wait for pageView to be laid out before paginating
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
        tvPageInfo.setText((currentPage + 1) + " / " + pageRenderer.getPageCount());
        seekBar.setProgress(currentPage);

        scheduleSave();
    }

    private void nextPage() {
        if (currentPage < pageRenderer.getPageCount() - 1) displayPage(currentPage + 1);
    }

    private void previousPage() {
        if (currentPage > 0) displayPage(currentPage - 1);
    }

    // ── Save: 5-second debounce ───────────────────────────────────────────────

    private void scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable);
        saveHandler.postDelayed(saveRunnable, 5000);
    }

    private void flushSaveNow() {
        saveHandler.removeCallbacks(saveRunnable);
        saveRunnable.run();
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    private void setupTouchHandler() {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                float x = e.getX();
                float w = pageView.getWidth();
                if (x < w / 3f) {
                    previousPage();
                    if (ttsActive) speakCurrentPage();
                } else if (x > 2f * w / 3f) {
                    nextPage();
                    if (ttsActive) speakCurrentPage();
                } else {
                    toggleBottomBar();
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

    private void toggleBottomBar() {
        uiVisible = !uiVisible;
        bottomBar.setVisibility(uiVisible ? View.VISIBLE : View.GONE);
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
            if (sizeChanged) {
                // Font size change requires re-pagination to maintain correct position
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
        flushSaveNow(); // don't wait for debounce when leaving the screen
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
