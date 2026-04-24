package com.example.minseo3;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * "즐겨찾기" 탭 (기존 "NAS" 탭 리네임). 두 섹션을 child fragment 로 감쌈:
 * "내 북마크" → {@link MyBookmarksFragment}
 * "다른 단말 진행" → {@link NasHistoryFragment}
 */
public class FavoritesFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_favorites, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.container_my_bookmarks, new MyBookmarksFragment())
                    .replace(R.id.container_nas_history, new NasHistoryFragment())
                    .commit();
        }
    }
}
