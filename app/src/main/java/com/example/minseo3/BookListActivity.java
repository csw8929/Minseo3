package com.example.minseo3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

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
        pagerAdapter = new PagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
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
                // position 0, 1 만 탭바 sync. 2 는 탭 highlight 없음 — 직전 상태 유지.
                if (position == 0 || position == 1) {
                    tabLayout.selectTab(tabLayout.getTabAt(position));
                }
                // position 2 도달 시 currentBookPath 가 null 이면 빈 상태로 보였다가
                // 사용자가 다시 왼쪽으로 돌아가도록 유도 — ReaderFragment.onResume 에서 처리.
            }
        });

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
