package com.example.minseo3;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "이 책의 북마크" 시트 — 리더 하단 ⭐ 탭 시 뜸.
 * - 상단 토글 버튼: 현재 페이지 북마크 추가/제거
 * - 리스트: 이 책에 쌓인 북마크 (alive only, 최신순)
 * - 항목 탭 → charOffset 점프, 휴지통 버튼 → soft-delete + undo snackbar
 */
public class BookmarkBottomSheet extends BottomSheetDialogFragment {

    public interface Listener {
        /** 사용자가 리스트에서 특정 북마크를 탭했을 때. */
        void onBookmarkJumpRequested(int charOffset);
        /** 현재 페이지의 [start, end) 범위 — 토글 버튼 누를 때 사용. */
        int getCurrentPageStart();
        int getCurrentPageEnd();
        /** 리더의 text 필드 — 프리뷰 추출용. */
        String getText();
        /** 현재 책 해시. */
        String getFileHash();
        /** 이 기기 id. */
        String getDeviceId();
    }

    private BookmarksRepository repo;
    private Listener listener;

    private RecyclerView rv;
    private Button btnToggle;
    private TextView tvEmpty;
    private Adapter adapter;

    public void bind(BookmarksRepository repo, Listener listener) {
        this.repo = repo;
        this.listener = listener;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle s) {
        return inflater.inflate(R.layout.bottom_sheet_bookmarks, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle s) {
        super.onViewCreated(v, s);
        rv = v.findViewById(R.id.rv_bookmarks);
        btnToggle = v.findViewById(R.id.btn_toggle_current_page);
        tvEmpty = v.findViewById(R.id.tv_empty_state);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        btnToggle.setOnClickListener(view -> onToggleClicked());

        refreshFromRepo();
    }

    private void onToggleClicked() {
        if (repo == null || listener == null) return;
        int start = listener.getCurrentPageStart();
        int end = listener.getCurrentPageEnd();
        repo.toggleAtPage(
                listener.getFileHash(), start, end,
                listener.getText(), listener.getDeviceId());
        refreshFromRepo();
    }

    private void refreshFromRepo() {
        if (repo == null || listener == null) return;
        List<Bookmark> list = repo.getAliveSortedByRecent(listener.getFileHash());
        adapter.submit(list);

        // Toggle button reflects "is this page currently bookmarked?"
        int start = listener.getCurrentPageStart();
        int end = listener.getCurrentPageEnd();
        boolean bookmarked = repo.anyInRange(listener.getFileHash(), start, end);
        btnToggle.setText(bookmarked ? R.string.bookmark_remove : R.string.bookmark_add);

        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void jumpTo(Bookmark b) {
        if (listener != null) listener.onBookmarkJumpRequested(b.charOffset);
        dismiss();
    }

    private void delete(Bookmark b) {
        if (repo == null || listener == null) return;
        repo.deleteById(listener.getFileHash(), b.id);
        refreshFromRepo();
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final SimpleDateFormat fmt = new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault());
        private List<Bookmark> items = java.util.Collections.emptyList();

        void submit(List<Bookmark> list) {
            this.items = list;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_bookmark, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Bookmark b = items.get(pos);
            h.tvPreview.setText(b.preview.isEmpty()
                    ? h.itemView.getContext().getString(R.string.bookmark_no_preview)
                    : b.preview);
            h.tvCreatedAt.setText(fmt.format(new Date(b.createdAt)));
            h.itemView.setOnClickListener(v -> jumpTo(b));
            h.btnDelete.setOnClickListener(v -> delete(b));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvPreview;
            final TextView tvCreatedAt;
            final ImageButton btnDelete;
            VH(@NonNull View v) {
                super(v);
                tvPreview = v.findViewById(R.id.tv_preview);
                tvCreatedAt = v.findViewById(R.id.tv_created_at);
                btnDelete = v.findViewById(R.id.btn_delete);
            }
        }
    }
}
