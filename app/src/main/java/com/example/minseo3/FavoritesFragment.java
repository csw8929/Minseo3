package com.example.minseo3;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * "즐겨찾기" 탭 (기존 "NAS" 탭 리네임). 두 섹션을 child fragment 로 감쌈:
 * "내 북마크" → {@link MyBookmarksFragment}
 * "다른 단말 진행" → {@link NasHistoryFragment}
 *
 * 각 섹션 타이틀 옆에 괄호로 항목 개수 표시. 자식 프래그먼트가 refresh 될 때마다
 * {@link #setMyBookmarkCount} / {@link #setOtherDeviceCount} 를 호출해 갱신.
 */
public class FavoritesFragment extends Fragment {

    private TextView tvSectionMyBookmarks;
    private TextView tvSectionOtherDevices;
    private int lastMyBookmarkCount = 0;
    private int lastOtherDeviceCount = 0;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_favorites, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvSectionMyBookmarks = view.findViewById(R.id.tv_section_my_bookmarks);
        tvSectionOtherDevices = view.findViewById(R.id.tv_section_other_devices);
        updateMyBookmarkHeader();
        updateOtherDeviceHeader();

        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.container_my_bookmarks, new MyBookmarksFragment())
                    .replace(R.id.container_nas_history, new NasHistoryFragment())
                    .commit();
        }
    }

    /** 자식 MyBookmarksFragment 가 refresh 할 때 호출. */
    public void setMyBookmarkCount(int count) {
        this.lastMyBookmarkCount = count;
        updateMyBookmarkHeader();
    }

    /** 자식 NasHistoryFragment 가 fetch 완료 시 호출. */
    public void setOtherDeviceCount(int count) {
        this.lastOtherDeviceCount = count;
        updateOtherDeviceHeader();
    }

    private void updateMyBookmarkHeader() {
        if (tvSectionMyBookmarks == null) return;
        tvSectionMyBookmarks.setText(
                getString(R.string.section_my_bookmarks) + " (" + lastMyBookmarkCount + ")");
    }

    private void updateOtherDeviceHeader() {
        if (tvSectionOtherDevices == null) return;
        tvSectionOtherDevices.setText(
                getString(R.string.section_other_devices) + " (" + lastOtherDeviceCount + ")");
    }
}
