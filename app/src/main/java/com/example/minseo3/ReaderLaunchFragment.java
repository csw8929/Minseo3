package com.example.minseo3;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * ViewPager2 의 3번째 페이지 (숨김 탭) — 즐겨찾기 탭에서 R→L 스와이프 시
 * 드러나는 "빈 오른쪽" 공간. 탭 전환 느낌의 horizontal slide 효과를 위해
 * 필요한 placeholder.
 *
 * 실제 리더 기동 / 자동 스냅백 로직은 {@link BookListActivity#onPageSelected}
 * 에서 position==2 가 되는 순간 처리. 이 프래그먼트는 검은 배경만 깔고 그
 * 순간을 visible 하게 만들어 줌.
 */
public class ReaderLaunchFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_reader_launch, container, false);
    }
}
