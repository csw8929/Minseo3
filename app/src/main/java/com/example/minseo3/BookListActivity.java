package com.example.minseo3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.File;

/**
 * 메인 목록 — 두 탭 (내 책 / 즐겨찾기). 즐겨찾기 탭에서 R→L fling 하면
 * 직전에 읽던 책이 horizontal slide 로 오픈됨 (탭 스와이프와 같은 방향 감각).
 *
 * 책을 한 번도 안 연 상태에서 R→L fling 은 no-op (ViewPager2 가 바운스).
 * 뒤로가기는 "내 책" 탭의 폴더 스택을 우선 소비한 뒤 기본 동작으로.
 */
public class BookListActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 100;

    private ViewPager2 viewPager;
    private PagerAdapter pagerAdapter;
    private GestureDetector swipeDetector;

    /** 세션 메모리 — 리더 진입 시 세팅. null 이면 swipe 해도 아무 일 없음. */
    private String lastOpenedBookPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_list);
        setTitle("소설 목록");

        new NasSyncManager(this);

        viewPager = findViewById(R.id.view_pager);
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        pagerAdapter = new PagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            tab.setText(position == 0 ? "내 책" : getString(R.string.tab_favorites));
        }).attach();

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

        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                // 즐겨찾기 탭 (position 1) 에서만 작동.
                if (viewPager.getCurrentItem() != 1) return false;
                if (lastOpenedBookPath == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = Math.abs(e2.getY() - e1.getY());
                // R→L fling: dx 음수, 가로 우세, 충분한 속도.
                if (dx < -200 && Math.abs(dx) > dy * 2 && Math.abs(vx) > 800) {
                    reopenLastBook();
                    return true;
                }
                return false;
            }
        });

        checkPermissions();
    }

    /** Fragment 에서 호출 — 사용자가 리더를 열 때 세션 메모리에 기록. */
    public void noteOpenedBook(String filePath) {
        this.lastOpenedBookPath = filePath;
    }

    /** 즐겨찾기에서 R→L 스와이프 → 직전에 읽던 책을 horizontal slide 로 오픈. */
    private void reopenLastBook() {
        if (lastOpenedBookPath == null) return;
        File file = new File(lastOpenedBookPath);
        if (!file.exists()) {
            lastOpenedBookPath = null;
            return;
        }
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, lastOpenedBookPath);
        ReaderActivity.startReader(this, intent);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // GestureDetector 에 전달만 하고 dispatch 는 그대로 진행 — 탭의 정상 스와이프 보존.
        if (swipeDetector != null) swipeDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
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

        @Override public int getItemCount() { return 2; }

        @NonNull @Override
        public Fragment createFragment(int position) {
            return position == 0 ? new BookListFragment() : new FavoritesFragment();
        }
    }
}
