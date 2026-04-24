package com.example.minseo3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.example.minseo3.nas.RemotePosition;
import com.example.minseo3.nas.RemoteProgressRepository;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.util.Map;

/**
 * 메인 화면 — ViewPager2 3 페이지:
 *   position 0  "내 책"      : BookListFragment
 *   position 1  "즐겨찾기"    : FavoritesFragment
 *   position 2  (탭 숨김)    : ReaderFragment (리더)
 *
 * 리더를 별도 Activity 에서 같은 Activity 내 Fragment 로 옮긴 이유: Activity
 * 전환은 OS pre-canned 애니라 드래그 응답이 불가능. ViewPager2 안에 두면
 * 어느 페이지 사이든 "드래그 중 두 화면 동시에 보이는" 탭 스와이프 감각 획득.
 *
 * 현재 열린 책 정보는 이 Activity 가 field 로 들고 있고 ReaderFragment 가
 * onResume 마다 읽어감. 탭바는 position 0, 1 만 표시 — position 2 는 스와이프나
 * 코드 호출로만 접근.
 */
public class BookListActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 100;

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private AppBarLayout appBar;
    private PagerAdapter pagerAdapter;

    // ── 현재 열린 책 상태 (ReaderFragment 가 onResume 에서 읽음) ─────────────
    @Nullable private String currentBookPath;
    private int currentBookStartOffset;
    private boolean currentBookSkipConflict;

    private static final String STATE_BOOK_PATH = "state_book_path";
    private static final String STATE_BOOK_OFFSET = "state_book_offset";
    private static final String STATE_BOOK_SKIP_CONFLICT = "state_book_skip_conflict";
    private static final String STATE_PAGER_POS = "state_pager_position";

    /** 앱 전체가 공유하는 북마크 저장소 — 리더와 즐겨찾기 탭이 같은 인스턴스를 봐야
     *  한쪽 변경이 즉시 다른 쪽 refresh 에 반영됨. Repository 는 in-memory 캐시를
     *  가지므로 인스턴스가 나뉘면 한쪽 mutation 이 다른 쪽에 보이지 않음. */
    @Nullable private BookmarksRepository bookmarksRepo;

    /** 앱 전체가 공유하는 진행률 저장소 — 리더에서 save 한 값을 "내 책" 탭에서
     *  %/시각으로 표시. BookmarksRepository 와 같은 이유로 싱글턴. */
    @Nullable private LocalProgressRepository progressRepo;

    /** 런치 결정에서 NAS fetch 를 쓰기 위해 보관. onCreate 생성, 프로세스 동안 유지. */
    @Nullable private NasSyncManager nasSyncManager;

    /** 이번 프로세스 시작 시 런치 결정 (auto-reader / popup) 을 이미 돌렸는지. */
    private boolean launchDecisionRan = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_list);

        // 회전 등 config change 후 복원 — 리더 페이지에 있던 사용자는 그대로 리더 유지.
        if (savedInstanceState != null) {
            currentBookPath = savedInstanceState.getString(STATE_BOOK_PATH);
            currentBookStartOffset = savedInstanceState.getInt(STATE_BOOK_OFFSET, 0);
            currentBookSkipConflict = savedInstanceState.getBoolean(STATE_BOOK_SKIP_CONFLICT, false);
        }

        nasSyncManager = new NasSyncManager(this);

        viewPager = findViewById(R.id.view_pager);
        tabLayout = findViewById(R.id.tab_layout);
        appBar = findViewById(R.id.app_bar);
        pagerAdapter = new PagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        // Window insets 수동 처리 — AppBar 가 상단 status bar padding 을 가져가고,
        // ViewPager2 는 즉시 AppBar 아래부터 시작. 리더 페이지(AppBar GONE) 이면
        // ViewPager2 가 edge-to-edge 로 확장 (status bar 영역까지 content 그림).
        View rootLayout = findViewById(R.id.root_layout);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // AppBar 는 status bar 가 보이는 페이지에서만 padding 받음 (리더 페이지는 GONE 이라 무영향).
            appBar.setPadding(0, sys.top, 0, 0);
            return insets;
        });
        // 3 페이지 모두 메모리 유지 — 탭 전환 시 fragment 재생성으로 리더 상태
        // 날아가는 것을 방지.
        viewPager.setOffscreenPageLimit(2);

        // 탭은 0, 1 만 수동 추가 (position 2 는 숨김 페이지).
        tabLayout.addTab(tabLayout.newTab().setText("내 책"));
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.tab_favorites)));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                viewPager.setCurrentItem(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                if (position == 0 || position == 1) {
                    tabLayout.selectTab(tabLayout.getTabAt(position));
                }
                applyChromeForPosition(position);
                // 마지막 종료 시점 탭 스냅샷 — 런치 결정 시 "리더로 자동 진입" 판단에 사용.
                AppSessionPrefs.setLastExitMode(BookListActivity.this,
                        position == 2 ? AppSessionPrefs.MODE_READER : AppSessionPrefs.MODE_LIST);
            }

            @Override public void onPageScrolled(int position, float offset, int px) {
                // 즐겨찾기(1) ↔ 리더(2) 스와이프 중에 AppBarLayout 을 부드럽게
                // 페이드 아웃. offset 은 position 1 에서 0 (즐겨찾기 현재) →
                // 1 (리더 완전 도달). 역방향 (리더 → 즐겨찾기) 도 대칭.
                if (position == 1) {
                    appBar.setAlpha(1f - offset);
                } else if (position == 0) {
                    appBar.setAlpha(1f);
                }
                // position 2 이상에서는 onPageSelected(2) 가 alpha=0 세팅 후 유지.
            }
        });

        // 초기 상태 설정 (앱 기동 시 position 0 이므로 chrome 보임).
        applyChromeForPosition(viewPager.getCurrentItem());
        applyTheme();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                int pos = viewPager.getCurrentItem();
                if (pos == 2) {
                    // 리더 → 즐겨찾기
                    navigateToFavorites();
                    return;
                }
                if (pos == 0) {
                    // 내 책 탭 — 폴더 스택 우선 소비
                    Fragment frag = getSupportFragmentManager().findFragmentByTag("f0");
                    if (frag instanceof BookListFragment && ((BookListFragment) frag).goUpIfPossible()) {
                        return;
                    }
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        checkPermissions();

        // 복원된 savedInstanceState 에 리더 페이지(position 2) 정보가 있으면 그 탭 유지.
        if (savedInstanceState != null) {
            int savedPos = savedInstanceState.getInt(STATE_PAGER_POS, 0);
            if (savedPos >= 0 && savedPos < 3) {
                viewPager.post(() -> viewPager.setCurrentItem(savedPos, false));
            }
        } else {
            // 콜드 스타트만 런치 결정 — 회전/복원 시엔 다시 팝업 띄우거나 자동 오픈하지 않음.
            runLaunchDecision();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 회전 등 config change 에서 book context 보존.
        outState.putString(STATE_BOOK_PATH, currentBookPath);
        outState.putInt(STATE_BOOK_OFFSET, currentBookStartOffset);
        outState.putBoolean(STATE_BOOK_SKIP_CONFLICT, currentBookSkipConflict);
        if (viewPager != null) {
            outState.putInt(STATE_PAGER_POS, viewPager.getCurrentItem());
        }
    }

    // ── Reader state accessors (ReaderFragment 가 사용) ─────────────────────

    @Nullable public String getCurrentBookPath() { return currentBookPath; }
    public int getCurrentBookStartOffset() { return currentBookStartOffset; }
    public boolean getCurrentBookSkipConflict() { return currentBookSkipConflict; }

    /** Fragment 에서 공유 북마크 repo 를 받아감 — lazy 생성, 단일 인스턴스 보장. */
    @NonNull public BookmarksRepository getBookmarksRepo() {
        if (bookmarksRepo == null) bookmarksRepo = new BookmarksRepository(this);
        return bookmarksRepo;
    }

    /** Fragment 에서 공유 진행률 repo 를 받아감. */
    @NonNull public LocalProgressRepository getProgressRepo() {
        if (progressRepo == null) progressRepo = new LocalProgressRepository(this);
        return progressRepo;
    }

    /**
     * 리더에서 테마 (배경/글자색) 가 바뀌었을 때 자식 프래그먼트들이 루트를 다시 칠
     * 하도록 트리거. Activity 자체 루트도 갱신. 자식은 onResume 타이밍에 ThemePrefs 를
     * 다시 읽으면 충분하므로 여기선 활성 페이지 fragment 에만 invalidate 요청.
     */
    public void applyTheme() {
        int bg = ThemePrefs.bgColor(this);
        findViewById(R.id.root_layout).setBackgroundColor(bg);
        // 활성 fragment 에게 테마 재적용 요청.
        for (Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof ThemedFragment) ((ThemedFragment) f).applyTheme();
        }
    }

    /** 테마가 바뀌었을 때 자신의 루트를 다시 칠할 수 있는 Fragment 인터페이스. */
    public interface ThemedFragment {
        void applyTheme();
    }

    /**
     * 책을 열고 리더 페이지로 이동. 모든 진입 경로 (내 책 탭 / 내 북마크 / 다른
     * 단말 진행) 가 이 메서드를 사용. ViewPager2 의 smoothScroll 로 horizontal
     * slide 애니 자동.
     */
    public void openBook(String filePath, int startOffset, boolean skipConflict) {
        this.currentBookPath = filePath;
        this.currentBookStartOffset = startOffset;
        this.currentBookSkipConflict = skipConflict;
        viewPager.setCurrentItem(2, true);
    }

    /** 리더에서 목록(즐겨찾기)으로 복귀. 책 상태는 유지 — 다음 swipe 로 재오픈 가능. */
    public void navigateToFavorites() {
        viewPager.setCurrentItem(1, true);
    }

    /** ReaderFragment 가 NAS 충돌 해결을 완료했을 때 호출 — 이후 rotation 등으로
     *  fragment 가 재생성되어도 다시 prompt 되지 않도록 host 수준으로 승격. */
    public void markConflictResolvedForCurrentBook() {
        currentBookSkipConflict = true;
    }

    // ── Launch decision ─────────────────────────────────────────────────────
    //
    // 콜드 스타트 직후 리스트 화면이 기본으로 뜬 상태에서, 한 번 실행:
    //
    //   1. NAS 최신 (다른 단말 deviceId) > Local 최신  → "이어보시겠습니까?" 팝업
    //   2. Local 최신 ≥ NAS 최신:
    //      - 마지막 종료가 리더 탭이었으면 → 해당 책 자동 리더 진입
    //      - 그 외 → 리스트 유지
    //
    // 회전 등 config change 시엔 이미 savedInstanceState != null 이므로 스킵.
    private void runLaunchDecision() {
        if (launchDecisionRan) return;
        launchDecisionRan = true;

        LocalProgressRepository.Entry local = getProgressRepo().getMostRecent();
        long localEpoch = local != null ? local.lastRead : 0L;
        String lastExit = AppSessionPrefs.getLastExitMode(this);

        NasSyncManager nas = nasSyncManager;
        if (nas == null || !nas.isEnabled() || !nas.isConnected()) {
            Log.i("NasSync", "SACH_NAS launch: nas off/not-connected — local only");
            applyLocalOnlyLaunchDecision(local, lastExit);
            return;
        }

        String myDeviceId = nas.deviceId();
        Handler main = new Handler(Looper.getMainLooper());
        nas.fetchAll(new RemoteProgressRepository.Callback<Map<String, RemotePosition>>() {
            @Override public void onResult(Map<String, RemotePosition> map) {
                main.post(() -> onLaunchNasLoaded(map, myDeviceId, local, localEpoch, lastExit));
            }
            @Override public void onError(String message) {
                Log.w("NasSync", "SACH_NAS launch fetchAll failed: " + message);
                main.post(() -> applyLocalOnlyLaunchDecision(local, lastExit));
            }
        });
    }

    private void onLaunchNasLoaded(Map<String, RemotePosition> map, String myDeviceId,
                                   @Nullable LocalProgressRepository.Entry local,
                                   long localEpoch,
                                   String lastExit) {
        // 사용자가 이미 직접 탭/스와이프로 이동했으면 자동 동작 취소 — 방해하지 않음.
        if (viewPager.getCurrentItem() != 0) {
            Log.i("NasSync", "SACH_NAS launch: user already navigated — skip");
            return;
        }

        RemotePosition nasBest = null;
        if (map != null) {
            for (RemotePosition p : map.values()) {
                if (p == null) continue;
                if (myDeviceId != null && myDeviceId.equals(p.deviceId)) continue; // 내 기기 제외
                if (nasBest == null || p.lastUpdatedEpoch > nasBest.lastUpdatedEpoch) nasBest = p;
            }
        }

        long nasEpoch = nasBest != null ? nasBest.lastUpdatedEpoch : 0L;
        Log.i("NasSync", "SACH_NAS launch compare: localEpoch=" + localEpoch
                + " nasEpoch=" + nasEpoch + " lastExit=" + lastExit);

        if (nasBest != null && nasEpoch > localEpoch) {
            showNasResumeDialog(nasBest);
            return;
        }
        applyLocalOnlyLaunchDecision(local, lastExit);
    }

    private void applyLocalOnlyLaunchDecision(@Nullable LocalProgressRepository.Entry local,
                                              String lastExit) {
        if (local == null) {
            Log.i("NasSync", "SACH_NAS launch: no local progress — stay on list");
            return;
        }
        if (!AppSessionPrefs.MODE_READER.equals(lastExit)) {
            Log.i("NasSync", "SACH_NAS launch: last exit was list — stay on list");
            return;
        }
        File f = new File(local.filePath);
        if (!f.exists()) {
            Log.i("NasSync", "SACH_NAS launch: local file missing " + local.filePath
                    + " — stay on list");
            return;
        }
        Log.i("NasSync", "SACH_NAS launch: auto-open reader " + local.filePath
                + " offset=" + local.charOffset);
        openBook(local.filePath, local.charOffset, /*skipConflict*/ true);
    }

    private void showNasResumeDialog(RemotePosition nas) {
        String title = stripTxt(nas.fileName);
        int percent = nas.totalChars > 0
                ? (int) (nas.charOffset * 100L / nas.totalChars) : 0;
        String msg = "다른 단말에서 '" + title + "' 을(를) " + percent
                + "% 까지 읽었습니다.\n이어 보시겠습니까?";

        new AlertDialog.Builder(this)
                .setTitle("이어 읽기")
                .setMessage(msg)
                .setPositiveButton("예", (d, w) -> {
                    File local = FileUtils.findLocalByNameAndSize(nas.fileName, nas.fileSize);
                    if (local == null) {
                        Toast.makeText(BookListActivity.this,
                                "이 기기에 '" + nas.fileName + "' 파일이 없습니다",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // NAS offset 으로 이미 진입하는 경로 — 리더 내부에서 재비교 불필요.
                    openBook(local.getAbsolutePath(), nas.charOffset, /*skipConflict*/ true);
                })
                .setNegativeButton("아니오", null)
                .setCancelable(true)
                .show();
    }

    private static String stripTxt(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase();
        return lower.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * 현재 페이지에 맞춰 AppBarLayout 표시 여부 + 시스템 bar (status/nav) 전체화면
     * 토글. 리더 페이지 (2) 에선 AppBar 숨김 + 시스템 bar 숨김 → 리더 content
     * 만으로 full screen. 0, 1 페이지는 일반 모드.
     */
    private void applyChromeForPosition(int position) {
        boolean readerPage = (position == 2);
        appBar.setAlpha(readerPage ? 0f : 1f);
        appBar.setVisibility(readerPage ? View.GONE : View.VISIBLE);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        // setDecorFitsSystemWindows(false) 는 onCreate 에서 한 번만 세팅. 토글하지 않음
        // (자체 insets listener 로 AppBar padding 을 관리).
        if (readerPage) {
            controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
        }
    }

    // ── Permissions (원본 유지) ─────────────────────────────────────────────

    public boolean hasStoragePermissionPublic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissions() {
        if (hasStoragePermissionPublic()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_STORAGE);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_STORAGE) reloadBookFragment();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_STORAGE) reloadBookFragment();
    }

    private void reloadBookFragment() {
        Fragment frag = getSupportFragmentManager().findFragmentByTag("f0");
        if (frag instanceof BookListFragment) ((BookListFragment) frag).reload();
    }

    // ── Pager ───────────────────────────────────────────────────────────────

    private static class PagerAdapter extends FragmentStateAdapter {
        PagerAdapter(@NonNull FragmentActivity activity) { super(activity); }

        @Override public int getItemCount() { return 3; }

        @NonNull @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:  return new BookListFragment();
                case 1:  return new FavoritesFragment();
                default: return new ReaderFragment();
            }
        }
    }
}
