package com.example.minseo3;

import android.app.Activity;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.minseo3.nas.BookmarksConflictResolver;
import com.example.minseo3.nas.ConflictOutcome;
import com.example.minseo3.nas.ConflictResolver;
import com.example.minseo3.nas.RemoteBookmarksRepository;
import com.example.minseo3.nas.RemoteProgressRepository;
import com.example.minseo3.nas.RemotePosition;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_CHAR_OFFSET = "char_offset";
    /** true 면 NAS 와의 충돌 해결을 건너뜀 — NAS 탭에서 진입한 경우 사용. */
    public static final String EXTRA_SKIP_CONFLICT_RESOLVE = "skip_conflict_resolve";

    /**
     * 리더 진입의 표준 경로 — 모든 호출자 (내 책 탭 / 즐겨찾기→리더 스와이프 /
     * 내 북마크 / 다른 단말 진행) 가 이 헬퍼 사용. 애니메이션은 **항상 horizontal
     * slide** (오른쪽에서 들어옴). 탭 스와이프와 같은 축 감각으로 통일.
     */
    public static void startReader(Activity from, Intent intent) {
        from.startActivity(intent);
        from.overridePendingTransition(R.anim.reader_enter_from_right, R.anim.reader_exit_to_left);
    }

    /** Fragment 용 — requireActivity() 통해 호출. */
    public static void startReaderFromFragment(androidx.fragment.app.Fragment from, Intent intent) {
        startReader(from.requireActivity(), intent);
    }

    // ── UI
    private PageView pageView;
    private TextView tvPageInfo;
    private TextView tvStatusLeft;
    private SeekBar seekBar;
    private View topBar, bottomBar, topMenuRow;
    private ImageButton btnBookmarks, btnTts, btnBack;

    // ── State
    private String text = "";
    private String fileHash = "";
    private String filePath = "";
    private int currentPage = 0;
    /** 상단 메뉴 + 하단바 표시 상태. 기본은 false (읽기 몰입용 최소 UI).
     *  상단 status strip (page % + 시계) 은 항상 표시. */
    private boolean uiVisible = false;

    private static final long UI_AUTO_HIDE_DELAY_MS = 3000L;
    private static final long UI_FADE_DURATION_MS = 200L;
    private final Handler uiHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiHideRunnable = this::hideUIBars;

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
    private PaginationCache paginationCache;

    // ── Bookmarks (Phase 1: local-only)
    private BookmarksRepository bookmarksRepo;
    private final Runnable bookmarksChangedListener = this::onBookmarksChanged;

    private void onBookmarksChanged() {
        if (paginationReady) refreshBookmarkIcon();
        if (!fileHash.isEmpty() && bookmarksRepo != null && nasSyncManager != null) {
            // Phase 2: push snapshot (alive + tombstone). NasSyncManager coalesces
            // rapid toggles via 1s debounce, so calling this on every mutation is safe.
            nasSyncManager.pushBookmarks(fileHash, bookmarksRepo.getAll(fileHash));
        }
    }

    /** Phase 2: pull remote bookmarks once per reader entry and merge into local. */
    private void maybePullBookmarks() {
        if (fileHash.isEmpty() || bookmarksRepo == null || nasSyncManager == null) return;
        if (!nasSyncManager.isEnabled() || !nasSyncManager.isConnected()) return;
        final String hashAtRequest = fileHash;
        nasSyncManager.pullBookmarks(hashAtRequest,
                new RemoteBookmarksRepository.Callback<List<Bookmark>>() {
                    @Override public void onResult(List<Bookmark> remote) {
                        mainHandler.post(() -> {
                            if (!hashAtRequest.equals(fileHash)) return; // activity moved on
                            if (remote == null || remote.isEmpty()) return;
                            List<Bookmark> local = bookmarksRepo.getAll(hashAtRequest);
                            List<Bookmark> merged = BookmarksConflictResolver.merge(local, remote);
                            bookmarksRepo.replaceAll(hashAtRequest, merged);
                        });
                    }
                    @Override public void onError(String msg) { /* lenient — 로컬 그대로 */ }
                });
    }

    // False while the full paginate is running. Blocks page navigation, seekbar
    // input, and TTS so we don't act on incomplete pageOffsets. flushSaveNow()
    // still saves using lastKnownOffset (no clobber).
    private boolean paginationReady = false;

    // Tracks the user's actual reading offset across paginate phases. Set by
    // paginate(int) and refreshed in displayPage. Used by save and re-paginate
    // when pageRenderer state isn't trustworthy yet.
    private int lastKnownOffset = 0;

    // Bumped on every paginate() call so stale main-thread callbacks from a
    // superseded pagination can detect they're outdated and bail out.
    private int paginateGeneration = 0;
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = () -> executor.execute(() -> {
        int offset = pageRenderer.getPageStartOffset(currentPage);
        progressRepo.save(fileHash, filePath, offset, text.length());
        nasSyncManager.push(fileHash, filePath, offset, text.length());
    });

    private TtsController tts;
    private boolean ttsActive = false;

    private boolean skipConflictResolve = false;
    private boolean conflictResolved = false;

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
        topMenuRow    = findViewById(R.id.top_menu_row);
        bottomBar     = findViewById(R.id.bottom_bar);

        // 기본 최소 UI — status strip 만 보임, 메뉴 행 + 하단바 숨김.
        topMenuRow.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        btnBookmarks  = findViewById(R.id.btn_bookmarks);
        btnTts        = findViewById(R.id.btn_tts);
        btnBack       = findViewById(R.id.btn_back);

        progressRepo = new LocalProgressRepository(this);
        nasSyncManager = new NasSyncManager(this);
        paginationCache = new PaginationCache(this);
        bookmarksRepo = new BookmarksRepository(this);
        bookmarksRepo.setOnChangedListener(bookmarksChangedListener);

        readerPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        textSizeSp = readerPrefs.getFloat(PREF_TEXT_SIZE_SP, textSizeSp);
        textColor  = readerPrefs.getInt(PREF_TEXT_COLOR, textColor);
        bgColor    = readerPrefs.getInt(PREF_BG_COLOR, bgColor);

        // Paint the saved background BEFORE the first frame so the reader
        // doesn't flash white while pagination runs.
        findViewById(R.id.reader_root).setBackgroundColor(bgColor);
        pageView.setColors(textColor, bgColor);

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
        skipConflictResolve = getIntent().getBooleanExtra(EXTRA_SKIP_CONFLICT_RESOLVE, false);

        if (filePath == null) { finish(); return; }

        File file = new File(filePath);
        fileHash = FileUtils.computeHash(file);
        setTitle(FileUtils.displayName(file));

        setupTouchHandler();
        setupSeekBar();
        setupTopMenu();
        btnBookmarks.setOnClickListener(v -> showBookmarks());
        btnTts.setOnClickListener(v -> toggleTts());
        btnBack.setOnClickListener(v -> finish());

        // Back press → return to list (BookListActivity is still in stack).
        // 스와이프 L→R / 하단 back 버튼 / 시스템 back 모두 같은 finish() 경로.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                finish();
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
        int sizeSpInt = (int) textSizeSp;

        lastKnownOffset = startOffset;
        paginationReady = false;
        seekBar.setEnabled(false);
        final int myGen = ++paginateGeneration;

        executor.execute(() -> {
            // 1) Cache hit → skip everything, jump straight to canonical page.
            int[] cached = paginationCache.load(fileHash, sizeSpInt, w, h);
            if (cached != null && cached.length > 0) {
                pageRenderer.setOffsets(cached);
                int page = pageRenderer.offsetToPage(startOffset);
                mainHandler.post(() -> {
                    if (myGen != paginateGeneration) return;
                    showLoading(false);
                    seekBar.setMax(Math.max(1, pageRenderer.getPageCount() - 1));
                    seekBar.setEnabled(true);
                    paginationReady = true;
                    displayPage(page);
                });
                return;
            }

            // 2) Cache miss → render first page from a small window so the
            //    user sees text immediately. Page count / seekbar stay deferred
            //    until full pagination completes.
            CharSequence firstPage = PageRenderer.computeFirstPageText(
                    text, startOffset, textSizePx, w, h);
            mainHandler.post(() -> {
                if (myGen != paginateGeneration) return;
                showLoading(false);
                pageView.setPage(firstPage, textSizePx, textColor, bgColor);
                tvPageInfo.setText("…");
                tvStatusLeft.setText("…");
                seekBar.setProgress(0);
            });

            // 3) Full paginate → cache → upgrade UI to canonical page.
            pageRenderer.paginate(text, textSizePx, w, h);
            int[] offsets = pageRenderer.getOffsetsArray();
            paginationCache.save(fileHash, sizeSpInt, w, h, offsets);
            int page = pageRenderer.offsetToPage(startOffset);
            mainHandler.post(() -> {
                if (myGen != paginateGeneration) return;
                seekBar.setMax(Math.max(1, pageRenderer.getPageCount() - 1));
                seekBar.setEnabled(true);
                paginationReady = true;
                displayPage(page);
                maybeResolveNasConflict();
                maybePullBookmarks();
            });
        });
    }

    // ── NAS 충돌 해결 (책 열 때 1회) ─────────────────────────────────────────

    private void maybeResolveNasConflict() {
        if (skipConflictResolve || conflictResolved) return;
        if (!nasSyncManager.isEnabled() || !nasSyncManager.isConnected()) return;
        conflictResolved = true;

        final int localOffset = pageRenderer.getPageStartOffset(currentPage);
        final LocalProgressRepository.Entry localEntry = progressRepo.get(fileHash);
        final long localLastRead = localEntry != null ? localEntry.lastRead : 0L;
        final String myDeviceId = nasSyncManager.deviceId();

        nasSyncManager.fetchOne(fileHash, new RemoteProgressRepository.Callback<RemotePosition>() {
            @Override public void onResult(RemotePosition nasPos) {
                mainHandler.post(() -> applyConflictOutcome(
                        ConflictResolver.resolve(localOffset, localLastRead, myDeviceId, nasPos),
                        nasPos));
            }
            @Override public void onError(String message) { /* 조용히 로컬 유지 */ }
        });
    }

    private void applyConflictOutcome(ConflictOutcome outcome, RemotePosition nasPos) {
        if (outcome.needsDialog) {
            showConflictDialog(nasPos);
            return;
        }
        int currentOffset = pageRenderer.getPageStartOffset(currentPage);
        if (outcome.resolvedOffset != currentOffset) {
            displayPage(pageRenderer.offsetToPage(outcome.resolvedOffset));
        }
    }

    private void showConflictDialog(RemotePosition nasPos) {
        int nasPercent = nasPos.totalChars > 0
                ? (int) (nasPos.charOffset * 100L / nasPos.totalChars) : 0;
        String deviceShort = nasPos.deviceId.length() >= 4
                ? nasPos.deviceId.substring(0, 4) : nasPos.deviceId;
        String msg = "다른 기기(" + deviceShort + ")에서 " + nasPercent + "% 지점까지 읽은 기록이 있습니다.\n"
                + "그 위치로 이동할까요?";
        new AlertDialog.Builder(this)
                .setTitle("이어 읽기")
                .setMessage(msg)
                .setPositiveButton("이동", (d, w) ->
                        displayPage(pageRenderer.offsetToPage(nasPos.charOffset)))
                .setNegativeButton("여기서 계속", null)
                .setCancelable(false)
                .show();
    }

    // ── Page display ─────────────────────────────────────────────────────────

    private void displayPage(int page) {
        if (page < 0) page = 0;
        if (page >= pageRenderer.getPageCount()) page = pageRenderer.getPageCount() - 1;
        currentPage = page;
        lastKnownOffset = pageRenderer.getPageStartOffset(currentPage);

        pageView.setPage(pageRenderer.getPageText(text, currentPage), spToPx(textSizeSp), textColor, bgColor);

        int total = pageRenderer.getPageCount();
        int percent = (total > 0) ? ((currentPage + 1) * 100 / total) : 0;
        String pageStr = (currentPage + 1) + " / " + total;
        tvPageInfo.setText(pageStr);
        tvStatusLeft.setText(pageStr + "  (" + percent + "%)");
        seekBar.setProgress(currentPage);

        refreshBookmarkIcon();
        scheduleSave();
    }

    /** Flip bottom-bar star between filled/outline based on whether this page is bookmarked. */
    private void refreshBookmarkIcon() {
        if (btnBookmarks == null || bookmarksRepo == null || fileHash.isEmpty()) return;
        int start = pageRenderer.getPageStartOffset(currentPage);
        int end = (currentPage + 1 < pageRenderer.getPageCount())
                ? pageRenderer.getPageStartOffset(currentPage + 1)
                : text.length();
        boolean filled = bookmarksRepo.anyInRange(fileHash, start, end);
        btnBookmarks.setImageResource(filled
                ? R.drawable.ic_bookmark_star_filled
                : R.drawable.ic_bookmark_star_outline);
    }

    /**
     * 별 탭 = 현재 페이지 북마크 즉시 토글 + 토스트 (등록됨 / 해제됨).
     * BottomSheet 팝업은 뜨지 않음. 이 책 북마크 목록을 보려면 즐겨찾기 탭 → "내 북마크".
     */
    private void showBookmarks() {
        if (!paginationReady || text.isEmpty() || bookmarksRepo == null) return;
        int start = pageRenderer.getPageStartOffset(currentPage);
        int end = (currentPage + 1 < pageRenderer.getPageCount())
                ? pageRenderer.getPageStartOffset(currentPage + 1)
                : text.length();
        boolean added = bookmarksRepo.toggleAtPage(
                fileHash, start, end, text, nasSyncManager.deviceId());
        Toast.makeText(this,
                added ? R.string.bookmark_registered : R.string.bookmark_unregistered,
                Toast.LENGTH_SHORT).show();
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
        if (!paginationReady) return;
        int max = pageRenderer.getPageCount();
        pendingPageDelta = Math.max(-max, Math.min(max, pendingPageDelta + delta));
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
        if (text.isEmpty()) return;
        // paginationReady=false (still loading) → fall back to lastKnownOffset
        // so we still refresh lastRead without clobbering the saved offset to 0.
        int offset = paginationReady
                ? pageRenderer.getPageStartOffset(currentPage)
                : lastKnownOffset;
        progressRepo.save(fileHash, filePath, offset, text.length());
        nasSyncManager.push(fileHash, filePath, offset, text.length());
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    private void setupTouchHandler() {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
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

            /**
             * 리더 L→R 스와이프 → finish() 로 리스트 복귀 (item 2).
             * 가로 우세 + 속도/거리 임계값을 넘어야 페이지 탭과 혼동되지 않음.
             */
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = Math.abs(e2.getY() - e1.getY());
                if (dx > 200 && Math.abs(dx) > dy * 2 && Math.abs(vx) > 800) {
                    finish();
                    return true;
                }
                return false;
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

    /**
     * 가운데 탭 → 현재 상태 토글.
     * 숨김 상태면 fade-in + 3초 후 auto-hide. 표시 상태면 즉시 fade-out.
     */
    private void toggleUIBars() {
        if (uiVisible) hideUIBars();
        else showUIBarsWithAutoHide();
    }

    private void showUIBarsWithAutoHide() {
        uiVisible = true;
        uiHideHandler.removeCallbacks(uiHideRunnable);
        fadeInToVisible(topMenuRow);
        fadeInToVisible(bottomBar);
        uiHideHandler.postDelayed(uiHideRunnable, UI_AUTO_HIDE_DELAY_MS);
    }

    private void hideUIBars() {
        uiVisible = false;
        uiHideHandler.removeCallbacks(uiHideRunnable);
        fadeOutToGone(topMenuRow);
        fadeOutToGone(bottomBar);
    }

    private void fadeInToVisible(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(UI_FADE_DURATION_MS).start();
    }

    private void fadeOutToGone(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.animate()
                .alpha(0f)
                .setDuration(UI_FADE_DURATION_MS)
                .withEndAction(() -> {
                    v.setVisibility(View.GONE);
                    v.setAlpha(1f); // reset for next show
                })
                .start();
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
                // Use lastKnownOffset, not getPageStartOffset(currentPage), because
                // pageRenderer can be in a partial/empty state (returns 0) if the
                // user changes size while the initial paginate is still running.
                showLoading(true);
                pageView.post(() -> paginate(lastKnownOffset));
            } else {
                pageView.setPage(pageRenderer.getPageText(text, currentPage), spToPx(textSizeSp), textColor, bgColor);
                pageView.setColors(textColor, bgColor);
            }
        });
        sheet.show(getSupportFragmentManager(), "settings");
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    private void toggleTts() {
        if (!paginationReady) return;
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
        if (bookmarksRepo != null) bookmarksRepo.clearOnChangedListener();
        uiHideHandler.removeCallbacks(uiHideRunnable);
        tts.shutdown();
        executor.shutdown();
    }

    /** 들어올 때의 반대 방향 — 리스트가 왼쪽에서 들어오고, 리더는 오른쪽으로 나감.
     *  진입이 horizontal 이었으므로 대칭으로 horizontal 이탈. */
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.reader_enter_from_left, R.anim.reader_exit_to_right);
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }
}
