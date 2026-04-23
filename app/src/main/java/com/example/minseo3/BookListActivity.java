package com.example.minseo3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.tabs.TabLayout;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_list);
        setTitle("소설 목록");

        new NasSyncManager(this);

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
    }

    // ── Reader state accessors (ReaderFragment 가 사용) ─────────────────────

    @Nullable public String getCurrentBookPath() { return currentBookPath; }
    public int getCurrentBookStartOffset() { return currentBookStartOffset; }
    public boolean getCurrentBookSkipConflict() { return currentBookSkipConflict; }

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
