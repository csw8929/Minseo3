package com.example.minseo3;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
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
import com.example.minseo3.tts.TtsPlaybackQueue;
import com.example.minseo3.tts.TtsPlaybackService;

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
    private TextView tvFileName;
    private SeekBar seekBar;
    private View topStatusStrip, bottomBar, topMenuRow, bottomFilenameStrip;
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
    private static final String PREF_TAP_SWAP     = "tap_swap";
    private static final String PREF_BOLD         = "bold_text";
    private static final String PREF_TTS_RATE     = "tts_speech_rate";

    private SharedPreferences readerPrefs;
    private float textSizeSp = 17f;
    private int textColor = 0xFF222222;
    private int bgColor = 0xFFFFFFFF;
    /** true 면 왼쪽 탭 = 다음 페이지, 오른쪽 탭 = 이전 페이지 (좌/우 기능 스왑). */
    private boolean tapSwap = false;
    private boolean bold = false;
    private float ttsRate = 1.0f;

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

    /** true 면 현재 {@code text} 가 prefix-only — 풀 텍스트 로딩이 아직 진행 중.
     *  설정 변경 시 이 플래그가 켜져있으면 in-memory paginate 가 아니라 loadFile 재실행. */
    private boolean partialMode = false;

    /** 현재 진행 중인 full paginate 의 취소 토큰. 새 paginate 시작 시 이전 것을 true 로 set. */
    private java.util.concurrent.atomic.AtomicBoolean paginateCancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = () -> executor.execute(() -> {
        if (text.isEmpty() || fileHash.isEmpty()) return;
        int offset = pageRenderer.getPageStartOffset(currentPage);
        progressRepo.save(fileHash, filePath, offset, text.length());
        nasSyncManager.push(fileHash, filePath, offset, text.length());
    });

    // ── TTS (Phase 2: Foreground Service + MediaSession) ─────────────────
    private TtsPlaybackService ttsService;
    private boolean ttsBound = false;
    private boolean ttsPendingPlayAfterBind = false;
    private boolean notifPermAsked = false;
    private final TtsPlaybackQueue ttsQueue = TtsPlaybackQueue.get();
    private int ttsState = TtsPlaybackService.STATE_IDLE;
    private ActivityResultLauncher<String> notifPermLauncher;

    private final ServiceConnection ttsConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            ttsService = ((TtsPlaybackService.LocalBinder) service).getService();
            ttsBound = true;
            ttsService.addStateListener(ttsStateListener);
            ttsState = ttsService.getState();
            // 저장된 사용자 속도 적용 — 서비스 default 1.0 을 user 값으로 덮음.
            ttsService.setSpeechRate(ttsRate);
            updateTtsButton();
            if (ttsPendingPlayAfterBind) {
                ttsPendingPlayAfterBind = false;
                ttsService.play();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            // 시스템이 서비스를 죽인 경우 — UI 만 정리. 다음 ▶ 탭에 다시 시작.
            if (ttsService != null) ttsService.removeStateListener(ttsStateListener);
            ttsService = null;
            ttsBound = false;
            ttsState = TtsPlaybackService.STATE_IDLE;
            updateTtsButton();
        }
    };

    private final TtsPlaybackService.StateListener ttsStateListener = state -> {
        ttsState = state;
        updateTtsButton();
    };

    private final TtsPlaybackQueue.Listener ttsQueueListener = new TtsPlaybackQueue.Listener() {
        @Override public void onPageChanged(int page) {
            // 자동 진행 / 외부 점프 모두 이 경로 — 같은 페이지면 무시 (사용자 탭으로 이미 이동한 경우).
            if (page == currentPage) return;
            displayPage(page);
        }
        @Override public void onLoadChanged() { /* Phase 1 no-op */ }
    };

    /** BookListActivity 의 volume key handler 가 참조 — TTS 중이면 시스템 볼륨이
     *  TTS 음량을 조절해야 하므로 인터셉트하지 않고 system 에 위임. */
    boolean isTtsActive() { return ttsState == TtsPlaybackService.STATE_PLAYING; }

    private boolean skipConflictResolve = false;
    private boolean conflictResolved = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ActivityResultLauncher 는 STARTED 전에 등록되어야 함.
        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (!granted) {
                        Toast.makeText(requireContext(),
                                "잠금화면 컨트롤 없이 음성만 재생됩니다.",
                                Toast.LENGTH_SHORT).show();
                    }
                    // 권한 결과 무관하게 재생 진행 — 노티만 안 보일 뿐.
                    actuallyPlayTts();
                });
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_reader, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        pageView       = view.findViewById(R.id.page_view);
        tvPageInfo     = view.findViewById(R.id.tv_page_info);
        tvStatusLeft   = view.findViewById(R.id.tv_status_left);
        tvFileName     = view.findViewById(R.id.tv_file_name);
        seekBar        = view.findViewById(R.id.seek_bar);
        topStatusStrip = view.findViewById(R.id.top_status_strip);
        topMenuRow     = view.findViewById(R.id.top_menu_row);
        bottomBar      = view.findViewById(R.id.bottom_bar);
        bottomFilenameStrip = view.findViewById(R.id.bottom_filename_strip);
        tvReaderEmpty  = view.findViewById(R.id.tv_reader_empty);
        btnBookmarks   = view.findViewById(R.id.btn_bookmarks);
        btnTts         = view.findViewById(R.id.btn_tts);
        btnBack        = view.findViewById(R.id.btn_back);

        // top_menu_row / bottom_bar 는 XML 에서 이미 visibility=gone. 건드릴 필요 없음.

        // 호스트 Activity 의 공유 repo 사용 — 다른 탭과 같은 인스턴스를 봐야
        // 한쪽 mutation 이 다른 쪽에 즉시 반영됨 (리스너 포함).
        progressRepo = ((BookListActivity) requireActivity()).getProgressRepo();
        nasSyncManager = new NasSyncManager(requireContext());
        paginationCache = new PaginationCache(requireContext());
        bookmarksRepo = ((BookListActivity) requireActivity()).getBookmarksRepo();
        bookmarksRepo.addChangedListener(bookmarksChangedListener);

        readerPrefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        textSizeSp = readerPrefs.getFloat(PREF_TEXT_SIZE_SP, textSizeSp);
        textColor  = readerPrefs.getInt(PREF_TEXT_COLOR, textColor);
        bgColor    = readerPrefs.getInt(PREF_BG_COLOR, bgColor);
        tapSwap    = readerPrefs.getBoolean(PREF_TAP_SWAP, false);
        bold       = readerPrefs.getBoolean(PREF_BOLD, false);
        ttsRate    = readerPrefs.getFloat(PREF_TTS_RATE, 1.0f);

        view.findViewById(R.id.reader_root).setBackgroundColor(bgColor);
        pageView.setColors(textColor, bgColor);
        pageView.setBold(bold);

        // TTS 서비스 바인딩 — Fragment view 가 살아있는 동안 유지. ▶ 탭 시 즉시 응답.
        ttsQueue.addListener(ttsQueueListener);
        Context appCtx = requireContext().getApplicationContext();
        appCtx.bindService(
                new Intent(appCtx, TtsPlaybackService.class),
                ttsConn, Context.BIND_AUTO_CREATE);

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
        // 시스템 TTS 설정에서 엔진을 바꿨다 돌아왔을 가능성 — 서비스가 자체 TextToSpeech 인스턴스
        // 를 들고 있으니 자동 갱신 안 됨. 명시적으로 체크.
        if (ttsBound && ttsService != null) ttsService.checkEngineChange();
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
        // 새 책 로드 (최초 오픈 혹은 rotation 후 복원).
        hideEmptyState();
        filePath = path;
        fileHash = FileUtils.computeHash(file);
        // Fresh open (popup/list/bookmark/NasHistory) 은 호출자가 명시적으로 정한
        // startOffset 을 신뢰. 회전 복원 시엔 host.currentBookStartOffset 이 "최초 오픈
        // 시점" 값이라 stale 이므로, onPause→flushSaveNow 가 갱신해둔 progressRepo 가
        // 최신 → 그쪽을 우선.
        LocalProgressRepository.Entry saved = progressRepo.get(fileHash);
        boolean freshOpen = host.isCurrentBookFreshOpen();
        int effectiveStartOffset;
        if (freshOpen) {
            effectiveStartOffset = startOffset;
        } else {
            effectiveStartOffset = (saved != null) ? saved.charOffset : startOffset;
        }
        skipConflictResolve = skipConflict;
        conflictResolved = false;
        loadedPath = path;
        tvFileName.setText(FileUtils.displayName(file));
        loadFile(file, effectiveStartOffset);
    }

    private void showEmptyState() {
        tvReaderEmpty.setVisibility(View.VISIBLE);
        pageView.setVisibility(View.GONE);
        topStatusStrip.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        bottomFilenameStrip.setVisibility(View.GONE);
    }

    private void hideEmptyState() {
        tvReaderEmpty.setVisibility(View.GONE);
        pageView.setVisibility(View.VISIBLE);
        topStatusStrip.setVisibility(View.VISIBLE);
        bottomFilenameStrip.setVisibility(View.VISIBLE);
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

    /**
     * 두 단계 로드.
     *
     * Phase 1 (prefix): 시작 오프셋 주변까지만 byte 를 읽어 디코드 → 부분 paginate →
     * 첫 페이지 즉시 표시. char offset 시스템과의 호환성 때문에 byte 0 부터 읽되
     * 끝 byte 는 {@code startByte_estimate + 64KB} 까지 (사용자가 앞뒤 페이지 이동
     * 가능하도록).
     *
     * Phase 2 (full): 백그라운드로 풀 read + decode → 캐시 또는 풀 paginate →
     * 메인스레드에서 pageRenderer.setOffsets + text 교체. 사용자의 currentPage 는
     * char offset 으로 다시 매핑.
     */
    private void loadFile(File file, int startOffset) {
        // pageView.post — pageView 가 layout 된 후에 width/height 측정. onResume 시점엔
        // 아직 0 일 수 있어서 한 프레임 기다림.
        pageView.post(() -> startLoadFlow(file, startOffset));
    }

    private void startLoadFlow(File file, int startOffset) {
        final int w = pageView.getWidth() - pageView.getPaddingLeft() - pageView.getPaddingRight();
        final int h = pageView.getHeight() - pageView.getPaddingTop() - pageView.getPaddingBottom();
        if (w <= 0 || h <= 0) {
            // 아직 layout 전. 한 번 더 대기.
            pageView.post(() -> startLoadFlow(file, startOffset));
            return;
        }

        showLoading(true);

        final float textSizePx = spToPx(textSizeSp);
        final int sizeSpInt = (int) textSizeSp;
        final boolean boldNow = bold;

        final int myGen = ++paginateGeneration;
        // 진행 중인 다른 paginate/loadFile 취소.
        paginateCancelled.set(true);
        final java.util.concurrent.atomic.AtomicBoolean myCancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        paginateCancelled = myCancelled;

        lastKnownOffset = startOffset;
        paginationReady = false;
        seekBar.setEnabled(false);

        executor.execute(() -> {
            try {
                // ── Phase 1: prefix read + decode + partial paginate ───────────
                long fileLen = file.length();
                // UTF-8 한글 보수적으로 byte ≈ char × 4. CP949 는 char × 2 라 과대추정 측이 안전.
                long startByteEst = (long) startOffset * 4L;
                long prefixTarget = Math.min(fileLen,
                        Math.max(128L * 1024L, startByteEst + 64L * 1024L));

                byte[] prefixBytes = FileUtils.readBytePrefix(file, prefixTarget);
                String partialText = FileUtils.decodeAutoPartial(prefixBytes);
                boolean isFull = (prefixBytes.length >= fileLen);

                // 추정이 어긋나 startOffset 까지 못 갔으면 256KB 씩 늘려가며 재시도.
                while (!isFull && partialText.length() < startOffset) {
                    long newLen = Math.min(fileLen, prefixBytes.length + 256L * 1024L);
                    if (newLen <= prefixBytes.length) break;
                    prefixBytes = FileUtils.readBytePrefix(file, newLen);
                    partialText = FileUtils.decodeAutoPartial(prefixBytes);
                    isFull = (prefixBytes.length >= fileLen);
                }

                if (myCancelled.get()) return;

                // partial paginate — 같은 pageRenderer 사용. 이후 풀 paginate 는 별도
                // PageRenderer 인스턴스로 돌려서 race 회피.
                pageRenderer.paginate(partialText, textSizePx, w, h, myCancelled, null, boldNow);
                if (myCancelled.get()) return;

                final int firstPage = pageRenderer.offsetToPage(startOffset);
                final String tPartial = partialText;
                final boolean tIsFull = isFull;
                mainHandler.post(() -> {
                    if (myGen != paginateGeneration) return;
                    text = tPartial;
                    partialMode = !tIsFull;
                    showLoading(false);
                    if (tIsFull) {
                        seekBar.setMax(Math.max(1, pageRenderer.getPageCount() - 1));
                        seekBar.setEnabled(true);
                    } else {
                        // partial — 페이지 수가 아직 미정. seekbar 비활성, "계산 중" 표시.
                        seekBar.setEnabled(false);
                        tvStatusLeft.setText("페이지 계산 중 0%");
                    }
                    paginationReady = true;
                    // TTS 큐에 책 등록 — partial 이라도 발화 가능 페이지가 있음. displayPage 가 곧 setCurrentPage 호출.
                    ttsQueue.load(text, pageRenderer.getOffsetsArray(), fileHash, FileUtils.displayName(new File(filePath)));
                    displayPage(firstPage);
                });

                if (tIsFull) {
                    // 작은 파일 — partial 이 곧 full. 캐시 저장 + NAS 로 끝.
                    paginationCache.save(fileHash, sizeSpInt, w, h, boldNow,
                            pageRenderer.getOffsetsArray());
                    mainHandler.post(() -> {
                        if (myGen != paginateGeneration) return;
                        maybeResolveNasConflict();
                        maybePullBookmarks();
                    });
                    return;
                }

                // ── Phase 2: full read + decode + (cache 또는 paginate) ─────────
                byte[] fullBytes = FileUtils.readAllBytes(file);
                String fullText = FileUtils.decodeAuto(fullBytes);
                if (myCancelled.get()) return;

                int[] cached = paginationCache.load(fileHash, sizeSpInt, w, h, boldNow);
                final int[] fullOffsets;
                if (cached != null && cached.length > 0) {
                    fullOffsets = cached;
                } else {
                    PageRenderer fullRenderer = new PageRenderer();
                    fullRenderer.paginate(fullText, textSizePx, w, h, myCancelled, pct -> {
                        mainHandler.post(() -> {
                            if (myGen != paginateGeneration || myCancelled.get()) return;
                            tvStatusLeft.setText("페이지 계산 중 " + pct + "%");
                        });
                    }, boldNow);
                    if (myCancelled.get()) return;
                    fullOffsets = fullRenderer.getOffsetsArray();
                    paginationCache.save(fileHash, sizeSpInt, w, h, boldNow, fullOffsets);
                }

                final String tFull = fullText;
                mainHandler.post(() -> {
                    if (myGen != paginateGeneration) return;
                    // 사용자의 현재 char offset 을 보존하고, 풀 offsets 로 교체한 뒤 재매핑.
                    int currentCharOffset = pageRenderer.getPageStartOffset(currentPage);
                    pageRenderer.setOffsets(fullOffsets);
                    text = tFull;
                    partialMode = false;
                    int newPage = pageRenderer.offsetToPage(currentCharOffset);
                    seekBar.setMax(Math.max(1, pageRenderer.getPageCount() - 1));
                    seekBar.setEnabled(true);
                    // TTS 큐에 풀 텍스트/오프셋 반영. displayPage 가 곧 setCurrentPage 로 새 페이지 통지.
                    ttsQueue.replaceOffsets(fullOffsets, tFull);
                    displayPage(newPage);
                    maybeResolveNasConflict();
                    maybePullBookmarks();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (myGen != paginateGeneration) return;
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

        // 이전에 돌고 있던 full paginate 가 있으면 즉시 취소 신호 — 새 파라미터로 재시작.
        paginateCancelled.set(true);
        final java.util.concurrent.atomic.AtomicBoolean myCancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        paginateCancelled = myCancelled;

        executor.execute(() -> {
            int[] cached = paginationCache.load(fileHash, sizeSpInt, w, h, bold);
            if (cached != null && cached.length > 0) {
                pageRenderer.setOffsets(cached);
                int page = pageRenderer.offsetToPage(startOffset);
                mainHandler.post(() -> {
                    if (myGen != paginateGeneration) return;
                    showLoading(false);
                    seekBar.setMax(Math.max(1, pageRenderer.getPageCount() - 1));
                    seekBar.setEnabled(true);
                    paginationReady = true;
                    ttsQueue.replaceOffsets(cached, text);
                    displayPage(page);
                });
                return;
            }

            // 새 태스크가 큐에서 꺼내지는 시점에 이미 또 다른 paginate 로 덮어써졌다면 조기 탈출.
            if (myCancelled.get()) return;

            CharSequence firstPage = PageRenderer.computeFirstPageText(
                    text, startOffset, textSizePx, w, h, bold);
            mainHandler.post(() -> {
                if (myGen != paginateGeneration) return;
                showLoading(false);
                pageView.setPage(firstPage, textSizePx, textColor, bgColor);
                tvPageInfo.setText("…");
                tvStatusLeft.setText("페이지 계산 중 0%");
                seekBar.setProgress(0);
            });

            pageRenderer.paginate(text, textSizePx, w, h, myCancelled, pct -> {
                mainHandler.post(() -> {
                    if (myGen != paginateGeneration) return;
                    if (myCancelled.get()) return;
                    tvStatusLeft.setText("페이지 계산 중 " + pct + "%");
                });
            }, bold);

            // 취소됐다면 pageOffsets 가 비어있으므로 cache save / displayPage 모두 스킵.
            if (myCancelled.get()) return;

            int[] offsets = pageRenderer.getOffsetsArray();
            paginationCache.save(fileHash, sizeSpInt, w, h, bold, offsets);
            int page = pageRenderer.offsetToPage(startOffset);
            mainHandler.post(() -> {
                if (myGen != paginateGeneration) return;
                seekBar.setMax(Math.max(1, pageRenderer.getPageCount() - 1));
                seekBar.setEnabled(true);
                paginationReady = true;
                ttsQueue.replaceOffsets(offsets, text);
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
        // Host 에 승격 — rotation 등으로 fragment 재생성되어도 다시 prompt 되지 않게.
        if (requireActivity() instanceof BookListActivity) {
            ((BookListActivity) requireActivity()).markConflictResolvedForCurrentBook();
        }

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
        // TTS 큐도 같은 페이지로 — 이미 같은 값이면 setCurrentPage 가 no-op.
        ttsQueue.setCurrentPage(currentPage);
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

    // ── Page-turn tap coalescing ─────────────────────────────────────────────
    private int pendingPageDelta = 0;
    private final Runnable applyPageDeltaRunnable = () -> {
        if (pendingPageDelta == 0) return;
        int target = currentPage + pendingPageDelta;
        pendingPageDelta = 0;
        displayPage(target);
        // TTS 재생 중 사용자 페이지 이동 시 자동 재발화는 displayPage→queue.setCurrentPage
        // → service queueListener 가 처리. 명시적으로 한 번 더 신호 — race 차단.
        if (ttsBound && ttsService != null && isTtsActive()) {
            ttsService.onPageMovedExternally();
        }
    };

    /** Volume key (BookListActivity.onKeyDown) / 탭 / TTS 자동 진행 모두 이 경로로
     *  들어와 60ms debounce 로 coalesce. package-private — 같은 패키지의 Activity 가
     *  외부 입력을 forward 할 때 사용. */
    void requestPageMove(int delta) {
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
                    requestPageMove(tapSwap ? +1 : -1);
                } else if (x > 2f * w / 3f) {
                    requestPageMove(tapSwap ? -1 : +1);
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
        SettingsBottomSheet sheet = SettingsBottomSheet.newInstance(textSizeSp, textColor, bgColor, tapSwap, bold, ttsRate);
        sheet.setListener(new SettingsBottomSheet.Listener() {
            @Override public void onTtsRateChanged(float rate) {
                ttsRate = rate;
                readerPrefs.edit().putFloat(PREF_TTS_RATE, ttsRate).apply();
                if (ttsBound && ttsService != null) ttsService.setSpeechRate(ttsRate);
            }
            @Override public void onOpenTtsEngineSettings() {
                openSystemTtsSettings();
            }
            @Override public void onSizePreview(float newSizeSp) {
                // 드래그 중 싼 프리뷰 — 현재 페이지 텍스트를 새 크기로 리드로우만 한다.
                // 페이지 경계는 옛 크기 기준이라 상/하가 잘리거나 남을 수 있지만 프리뷰 용도이므로 OK.
                // textSizeSp 필드 / prefs 는 건드리지 않음 (아직 commit 전).
                pageView.setTextSizePx(spToPx(newSizeSp));
            }

            @Override public void onChanged(float newSizeSp, int newTextColor, int newBgColor, boolean newTapSwap, boolean newBold) {
                boolean sizeChanged = newSizeSp != textSizeSp;
                boolean boldChanged = newBold != bold;
                textSizeSp = newSizeSp;
                textColor = newTextColor;
                bgColor = newBgColor;
                tapSwap = newTapSwap;
                bold = newBold;
                readerPrefs.edit()
                        .putFloat(PREF_TEXT_SIZE_SP, textSizeSp)
                        .putInt(PREF_TEXT_COLOR, textColor)
                        .putInt(PREF_BG_COLOR, bgColor)
                        .putBoolean(PREF_TAP_SWAP, tapSwap)
                        .putBoolean(PREF_BOLD, bold)
                        .apply();
                pageView.setBold(bold);
                if (sizeChanged || boldChanged) {
                    showLoading(true);
                    if (partialMode) {
                        // 풀 텍스트가 아직 로드 중. paginate(in-memory) 로는 partial 만 처리되니
                        // loadFile 을 다시 호출해서 partial→full 흐름 전체 재시작.
                        loadFile(new File(filePath), lastKnownOffset);
                    } else {
                        pageView.post(() -> paginate(lastKnownOffset));
                    }
                } else {
                    pageView.setPage(pageRenderer.getPageText(text, currentPage), spToPx(textSizeSp), textColor, bgColor);
                    pageView.setColors(textColor, bgColor);
                }
                requireView().findViewById(R.id.reader_root).setBackgroundColor(bgColor);
                // 호스트 Activity 에 테마 바뀐 사실 통지 — 내 책 / 즐겨찾기 페이지도 같은 bg.
                if (requireActivity() instanceof BookListActivity) {
                    ((BookListActivity) requireActivity()).applyTheme();
                }
            }
        });
        sheet.show(getChildFragmentManager(), "settings");
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    private void toggleTts() {
        if (!paginationReady) return;
        // pause 분기 — 권한/서비스 시작 모두 불필요.
        if (ttsBound && ttsService != null && ttsState == TtsPlaybackService.STATE_PLAYING) {
            ttsService.pause();
            return;
        }
        // play 분기 — 첫 재생 시 알림 권한 한 번 요청 (API 33+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !notifPermAsked
                && ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifPermAsked = true;
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;  // 콜백에서 actuallyPlayTts 호출.
        }
        actuallyPlayTts();
    }

    /**
     * 시스템 TTS 설정 화면 열기. AOSP 표준 → OEM 별칭 → 보이스 입력 → 일반 설정 → 토스트 폴백.
     * Samsung One UI 의 변종까지 포괄.
     */
    private void openSystemTtsSettings() {
        String[] actions = {
                "com.android.settings.TTS_SETTINGS",
                "android.settings.TTS_SETTINGS",
        };
        android.content.pm.PackageManager pm = requireContext().getPackageManager();
        for (String action : actions) {
            Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(pm) != null) {
                startActivity(intent);
                return;
            }
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception ignored) {}
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception ignored) {}
        Toast.makeText(requireContext(),
                "기기 설정 → 일반 → 언어 → TTS 에서 변경하세요",
                Toast.LENGTH_LONG).show();
    }

    /** 권한 체크 통과 후 실제 재생 시작 — startForegroundService + 서비스 play. */
    private void actuallyPlayTts() {
        Context appCtx = requireContext().getApplicationContext();
        try {
            ContextCompat.startForegroundService(appCtx,
                    new Intent(appCtx, TtsPlaybackService.class));
        } catch (Exception e) {
            // API 31+ 백그라운드 시작 제한 / FGS 시작 차단 등 — bound service 만으로 폴백.
            Toast.makeText(requireContext(),
                    "백그라운드 재생을 시작할 수 없어 화면 켜둔 상태로만 재생됩니다.",
                    Toast.LENGTH_SHORT).show();
        }
        if (ttsBound && ttsService != null) {
            ttsService.play();
        } else {
            ttsPendingPlayAfterBind = true;
        }
    }

    private void updateTtsButton() {
        if (btnTts == null) return;
        boolean playing = (ttsState == TtsPlaybackService.STATE_PLAYING);
        btnTts.setImageResource(playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onPause() {
        super.onPause();
        requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Phase 2 — 음성은 foreground service 로 계속. 화면만 sleep 허용.
        flushSaveNow();
    }

    @Override
    public void onDestroyView() {
        // listener 해제는 super 보다 먼저 — super.onDestroyView 중 콜백이 뜨면 getView() null.
        if (bookmarksRepo != null) bookmarksRepo.removeChangedListener(bookmarksChangedListener);
        uiHideHandler.removeCallbacks(uiHideRunnable);
        ttsQueue.removeListener(ttsQueueListener);
        if (ttsBound) {
            if (ttsService != null) {
                ttsService.removeStateListener(ttsStateListener);
                // Phase 2: 서비스는 foreground 상태로 살려둠. 사용자 명시 정지(노티 stop) /
                // 책 끝 도달 / 다른 앱이 AudioFocus 영구 가져감 시 stopSelf.
            }
            requireContext().getApplicationContext().unbindService(ttsConn);
            ttsBound = false;
            ttsService = null;
        }
        super.onDestroyView();
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
