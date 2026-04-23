package com.example.minseo3;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

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

/**
 * 리더 뷰 — BookListActivity 의 ViewPager2 3번째 페이지.
 *
 * 이전에는 별도 Activity (ReaderActivity) 였지만, 즐겨찾기↔리더 스와이프 전환
 * 시 "두 화면이 동시에 드래그 중 보이는" 탭 스와이프 느낌을 내기 위해 Fragment
 * 로 전환. Activity 전환은 OS pre-canned 애니라 드래그 응답이 불가능.
 *
 * 현재 열린 책 정보는 호스트 {@link BookListActivity} 의 {@code currentBook*} 필드에서
 * onResume 마다 읽어온다. 같은 책이면 no-op, 다른 책이면 reload.
 */
public class ReaderFragment extends Fragment {

    // ── UI
    private PageView pageView;
    private TextView tvPageInfo;
    private TextView tvStatusLeft;
    private SeekBar seekBar;
    private View topBar, bottomBar, topMenuRow;
    private View tvReaderEmpty;
    private ImageButton btnBookmarks, btnTts, btnBack;

    // ── State
    private String text = "";
    private String fileHash = "";
    private String filePath = "";
    private String loadedPath = ""; // 마지막으로 loadFile 한 경로 — 같으면 reload skip
    private int currentPage = 0;
    private boolean uiVisible = false;

    private static final long UI_AUTO_HIDE_DELAY_MS = 3000L;
    private static final long UI_FADE_DURATION_MS = 200L;
    private final Handler uiHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiHideRunnable = this::hideUIBars;

    // ── Settings
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

    // ── Repos
    private LocalProgressRepository progressRepo;
    private NasSyncManager nasSyncManager;
    private PaginationCache paginationCache;
    private BookmarksRepository bookmarksRepo;
    private final Runnable bookmarksChangedListener = this::onBookmarksChanged;

    private boolean paginationReady = false;
    private int lastKnownOffset = 0;
    private int paginateGeneration = 0;

    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = () -> executor.execute(() -> {
        if (text.isEmpty() || fileHash.isEmpty()) return;
        int offset = pageRenderer.getPageStartOffset(currentPage);
        progressRepo.save(fileHash, filePath, offset, text.length());
        nasSyncManager.push(fileHash, filePath, offset, text.length());
    });

    private TtsController tts;
    private boolean ttsActive = false;

    private boolean skipConflictResolve = false;
    private boolean conflictResolved = false;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_reader, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        pageView      = view.findViewById(R.id.page_view);
        tvPageInfo    = view.findViewById(R.id.tv_page_info);
        tvStatusLeft  = view.findViewById(R.id.tv_status_left);
        seekBar       = view.findViewById(R.id.seek_bar);
        topBar        = view.findViewById(R.id.top_bar);
        topMenuRow    = view.findViewById(R.id.top_menu_row);
        bottomBar     = view.findViewById(R.id.bottom_bar);
        tvReaderEmpty = view.findViewById(R.id.tv_reader_empty);
        btnBookmarks  = view.findViewById(R.id.btn_bookmarks);
        btnTts        = view.findViewById(R.id.btn_tts);
        btnBack       = view.findViewById(R.id.btn_back);

        topMenuRow.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);

        progressRepo = new LocalProgressRepository(requireContext());
        nasSyncManager = new NasSyncManager(requireContext());
        paginationCache = new PaginationCache(requireContext());
        bookmarksRepo = new BookmarksRepository(requireContext());
        bookmarksRepo.setOnChangedListener(bookmarksChangedListener);

        readerPrefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        textSizeSp = readerPrefs.getFloat(PREF_TEXT_SIZE_SP, textSizeSp);
        textColor  = readerPrefs.getInt(PREF_TEXT_COLOR, textColor);
        bgColor    = readerPrefs.getInt(PREF_BG_COLOR, bgColor);

        view.findViewById(R.id.reader_root).setBackgroundColor(bgColor);
        pageView.setColors(textColor, bgColor);

        tts = new TtsController(requireContext(), new TtsController.Listener() {
            @Override public void onReady() {}
            @Override public void onDone() {
                if (ttsActive) mainHandler.post(() -> nextPage());
            }
            @Override public void onError() {
                mainHandler.post(() -> { ttsActive = false; updateTtsButton(); });
            }
        });

        setupTouchHandler();
        setupSeekBar();
        setupTopMenu();
        btnBookmarks.setOnClickListener(v -> showBookmarks());
        btnTts.setOnClickListener(v -> toggleTts());
        btnBack.setOnClickListener(v -> returnToFavorites());
    }

    @Override
    public void onResume() {
        super.onResume();
        // 리더 화면이 실제로 보일 때만 screen-on (ViewPager2 다른 페이지 활성 시 clear).
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        loadCurrentBookFromHost();
    }

    /** Host Activity 에서 현재 열린 책 정보를 받아와 필요 시 reload. */
    private void loadCurrentBookFromHost() {
        if (!(requireActivity() instanceof BookListActivity)) return;
        BookListActivity host = (BookListActivity) requireActivity();
        String path = host.getCurrentBookPath();
        int startOffset = host.getCurrentBookStartOffset();
        boolean skipConflict = host.getCurrentBookSkipConflict();

        if (path == null || path.isEmpty()) {
            showEmptyState();
            return;
        }
        File file = new File(path);
        if (!file.exists()) {
            showEmptyState();
            return;
        }
        if (path.equals(loadedPath)) {
            // 같은 책 — 이미 로드돼있음. 스킵.
            return;
        }
        // 새 책 로드
        hideEmptyState();
        filePath = path;
        fileHash = FileUtils.computeHash(file);
        skipConflictResolve = skipConflict;
        conflictResolved = false;
        loadedPath = path;
        loadFile(file, startOffset);
    }

    private void showEmptyState() {
        tvReaderEmpty.setVisibility(View.VISIBLE);
        pageView.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
    }

    private void hideEmptyState() {
        tvReaderEmpty.setVisibility(View.GONE);
        pageView.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
        // bottomBar 는 uiVisible 에 따라 관리
    }

    /** 리스트로 복귀 (back 버튼 / 시스템 back). BookListActivity 의 1번 탭으로. */
    void returnToFavorites() {
        if (requireActivity() instanceof BookListActivity) {
            ((BookListActivity) requireActivity()).navigateToFavorites();
        }
    }

    private void setupTopMenu() {
        requireView().findViewById(R.id.menu_settings).setOnClickListener(v -> showSettings());
        requireView().findViewById(R.id.menu_nas).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), NasSettingsActivity.class)));
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
                    Toast.makeText(requireContext(),
                            "파일을 읽을 수 없습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    returnToFavorites();
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

    // ── NAS 충돌 해결 ────────────────────────────────────────────────────────

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
        if (!isAdded()) return;
        int nasPercent = nasPos.totalChars > 0
                ? (int) (nasPos.charOffset * 100L / nasPos.totalChars) : 0;
        String deviceShort = nasPos.deviceId.length() >= 4
                ? nasPos.deviceId.substring(0, 4) : nasPos.deviceId;
        String msg = "다른 기기(" + deviceShort + ")에서 " + nasPercent + "% 지점까지 읽은 기록이 있습니다.\n"
                + "그 위치로 이동할까요?";
        new AlertDialog.Builder(requireContext())
                .setTitle("이어 읽기")
                .setMessage(msg)
                .setPositiveButton("이동", (d, w) ->
                        displayPage(pageRenderer.offsetToPage(nasPos.charOffset)))
                .setNegativeButton("여기서 계속", null)
                .setCancelable(false)
                .show();
    }

    private void onBookmarksChanged() {
        if (paginationReady) refreshBookmarkIcon();
        if (!fileHash.isEmpty() && bookmarksRepo != null && nasSyncManager != null) {
            nasSyncManager.pushBookmarks(fileHash, bookmarksRepo.getAll(fileHash));
        }
    }

    private void maybePullBookmarks() {
        if (fileHash.isEmpty() || bookmarksRepo == null || nasSyncManager == null) return;
        if (!nasSyncManager.isEnabled() || !nasSyncManager.isConnected()) return;
        final String hashAtRequest = fileHash;
        nasSyncManager.pullBookmarks(hashAtRequest,
                new RemoteBookmarksRepository.Callback<List<Bookmark>>() {
                    @Override public void onResult(List<Bookmark> remote) {
                        mainHandler.post(() -> {
                            if (!hashAtRequest.equals(fileHash)) return;
                            if (remote == null || remote.isEmpty()) return;
                            List<Bookmark> local = bookmarksRepo.getAll(hashAtRequest);
                            List<Bookmark> merged = BookmarksConflictResolver.merge(local, remote);
                            bookmarksRepo.replaceAll(hashAtRequest, merged);
                        });
                    }
                    @Override public void onError(String msg) { /* lenient */ }
                });
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

    private void showBookmarks() {
        if (!paginationReady || text.isEmpty() || bookmarksRepo == null) return;
        int start = pageRenderer.getPageStartOffset(currentPage);
        int end = (currentPage + 1 < pageRenderer.getPageCount())
                ? pageRenderer.getPageStartOffset(currentPage + 1)
                : text.length();
        boolean added = bookmarksRepo.toggleAtPage(
                fileHash, start, end, text, nasSyncManager.deviceId());
        Toast.makeText(requireContext(),
                added ? R.string.bookmark_registered : R.string.bookmark_unregistered,
                Toast.LENGTH_SHORT).show();
    }

    private void nextPage() {
        if (currentPage < pageRenderer.getPageCount() - 1) displayPage(currentPage + 1);
    }

    // ── Page-turn tap coalescing ─────────────────────────────────────────────
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

    // ── Save ─────────────────────────────────────────────────────────────────

    private void scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable);
        saveHandler.postDelayed(saveRunnable, 5000);
    }

    private void flushSaveNow() {
        saveHandler.removeCallbacks(saveRunnable);
        if (text.isEmpty() || fileHash.isEmpty()) return;
        int offset = paginationReady
                ? pageRenderer.getPageStartOffset(currentPage)
                : lastKnownOffset;
        progressRepo.save(fileHash, filePath, offset, text.length());
        nasSyncManager.push(fileHash, filePath, offset, text.length());
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    private void setupTouchHandler() {
        GestureDetector detector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
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
                    v.setAlpha(1f);
                })
                .start();
    }

    private void showLoading(boolean show) {
        View loading = getView() != null ? getView().findViewById(R.id.loading) : null;
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
                showLoading(true);
                pageView.post(() -> paginate(lastKnownOffset));
            } else {
                pageView.setPage(pageRenderer.getPageText(text, currentPage), spToPx(textSizeSp), textColor, bgColor);
                pageView.setColors(textColor, bgColor);
            }
        });
        sheet.show(getChildFragmentManager(), "settings");
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

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onPause() {
        super.onPause();
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (tts != null) tts.stop();
        ttsActive = false;
        flushSaveNow();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bookmarksRepo != null) bookmarksRepo.clearOnChangedListener();
        uiHideHandler.removeCallbacks(uiHideRunnable);
        if (tts != null) tts.shutdown();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
    }
}
