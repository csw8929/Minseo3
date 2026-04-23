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

import java.io.File;

/**
 * 메인 목록 화면 — ViewPager2 구조:
 *   position 0  "내 책"       : BookListFragment
 *   position 1  "즐겨찾기"     : FavoritesFragment
 *   position 2  (탭 숨김)     : ReaderLaunchFragment — 즐겨찾기에서 R→L 스와이프
 *                               시 드러나는 페이지. onPageSelected 에서 처리:
 *                                 - 직전에 읽은 책 있음 → 리더 horizontal 오픈 + 1 로 복귀
 *                                 - 없음 → 빈 화면 300ms 보여주고 1 로 자동 스냅백.
 *
 * 탭바는 0, 1 두 개만 표시. 3번째는 스와이프로만 접근. 뒤로가기는 "내 책" 탭의
 * 폴더 스택을 우선 소비한 뒤 기본 동작으로 빠짐.
 */
public class BookListActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 100;

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private PagerAdapter pagerAdapter;

    /** 리더 진입 시 세팅되는 세션 메모리 — 즐겨찾기→리더 스와이프 대상.
     *  앱 시작 직후엔 null (스와이프 해도 빈 화면만 보였다가 복귀). */
    private String lastOpenedBookPath;

    /** 리더에서 돌아왔을 때 즐겨찾기 탭으로 복귀시키기 위한 플래그
     *  (position 2 에서 reader 띄운 뒤 onResume 에서 1 로 복귀). */
    private boolean pendingResetToFavorites = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_list);
        setTitle("소설 목록");

        // NasSyncManager 를 앱 시작 시점에 한 번 구성해 seeding / LAN top-up 이 실행되도록 함.
        new NasSyncManager(this);

        viewPager = findViewById(R.id.view_pager);
        tabLayout = findViewById(R.id.tab_layout);
        pagerAdapter = new PagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        // 탭은 0, 1 두 개만 수동으로. 3번째 페이지는 스와이프로만 접근.
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
            @Override
            public void onPageSelected(int position) {
                if (position == 0 || position == 1) {
                    tabLayout.selectTab(tabLayout.getTabAt(position));
                }
                if (position == 2) {
                    handleReaderLaunchPage();
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (viewPager.getCurrentItem() == 0) {
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

    @Override
    protected void onResume() {
        super.onResume();
        // 즐겨찾기→리더 진입 후 돌아왔을 때 position 을 1 (즐겨찾기) 로 복귀.
        if (pendingResetToFavorites) {
            pendingResetToFavorites = false;
            if (viewPager.getCurrentItem() != 1) {
                viewPager.setCurrentItem(1, false);
            }
        }
    }

    /** Fragment 에서 호출 — 사용자가 리더를 열 때 세션 메모리에 기록. */
    public void noteOpenedBook(String filePath) {
        this.lastOpenedBookPath = filePath;
    }

    /** 즐겨찾기→리더 스와이프 도달 (position 2) 시 호출. */
    private void handleReaderLaunchPage() {
        if (lastOpenedBookPath != null && new File(lastOpenedBookPath).exists()) {
            launchReaderHorizontally(lastOpenedBookPath);
        } else {
            // 파일 없으면 기록 폐기 + 잠시 빈 화면 → 즐겨찾기로 자동 스냅백.
            lastOpenedBookPath = null;
            viewPager.postDelayed(() -> {
                if (viewPager.getCurrentItem() == 2) {
                    viewPager.setCurrentItem(1, true);
                }
            }, 300);
        }
    }

    /** 즐겨찾기 탭에서 스와이프로 리더 오픈 — 항상 horizontal 슬라이드 (탭 전환 느낌). */
    private void launchReaderHorizontally(String filePath) {
        pendingResetToFavorites = true;
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, filePath);
        intent.putExtra(ReaderActivity.EXTRA_ENTER_ANIMATION, ReaderActivity.ANIM_HORIZONTAL);
        startActivity(intent);
        overridePendingTransition(R.anim.reader_enter_from_right, R.anim.reader_exit_to_left);
    }

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

    private static class PagerAdapter extends FragmentStateAdapter {
        PagerAdapter(@NonNull FragmentActivity activity) { super(activity); }

        @Override public int getItemCount() { return 3; }

        @NonNull @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:  return new BookListFragment();
                case 1:  return new FavoritesFragment();
                default: return new ReaderLaunchFragment();
            }
        }
    }
}
