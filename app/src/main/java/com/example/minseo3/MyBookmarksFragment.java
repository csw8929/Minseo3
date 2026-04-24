package com.example.minseo3;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 즐겨찾기 탭의 "내 북마크" 섹션 — 모든 책의 alive 북마크 cross-book flat 리스트.
 * 항목 = (책 제목, 프리뷰, 생성 시각). 탭 → ReaderActivity 해당 charOffset 점프.
 *
 * 책 제목은 {@link LocalProgressRepository} 에서 fileHash → filePath 를 조회해 파생.
 * 로컬에 파일이 없는 (다른 기기에서만 본) 책은 탭 시 토스트.
 */
public class MyBookmarksFragment extends Fragment implements BookListActivity.ThemedFragment {

    private Adapter adapter;
    private TextView tvEmpty;
    private BookmarksRepository bmRepo;

    /** 리더에서 북마크 토글/삭제 시 main 스레드로 호출 → 리스트 즉시 갱신. */
    private final Runnable onBookmarksChanged = () -> {
        if (getView() != null) refresh();
    };

    @Override public void applyTheme() {
        View v = getView();
        if (v != null) v.setBackgroundColor(ThemePrefs.bgColor(requireContext()));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_my_bookmarks, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView rv = view.findViewById(R.id.rv_my_bookmarks);
        tvEmpty = view.findViewById(R.id.tv_empty);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        // 공유 BookmarksRepository 리스너 등록 — 리더에서 토글 시 즉시 리스트 갱신.
        // (기존엔 onResume 에서만 refresh — 스와이프 타이밍 / 중첩 fragment 수명주기 전파
        // 지연으로 간헐적으로 stale 하던 증상이 여기서 해결됨.)
        if (requireActivity() instanceof BookListActivity) {
            bmRepo = ((BookListActivity) requireActivity()).getBookmarksRepo();
            bmRepo.addChangedListener(onBookmarksChanged);
        }
        applyTheme();
    }

    @Override
    public void onDestroyView() {
        if (bmRepo != null) bmRepo.removeChangedListener(onBookmarksChanged);
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        // 공유 repo 들은 onViewCreated 에서 이미 잡았지만, 리스너 콜백 경로로 진입한
        // 경우에도 안전하도록 null 체크 + 재바인딩.
        if (bmRepo == null && getActivity() instanceof BookListActivity) {
            bmRepo = ((BookListActivity) getActivity()).getBookmarksRepo();
        }
        if (bmRepo == null) return;
        LocalProgressRepository progressRepo = (getActivity() instanceof BookListActivity)
                ? ((BookListActivity) getActivity()).getProgressRepo()
                : new LocalProgressRepository(requireContext());

        Map<String, List<Bookmark>> byHash = bmRepo.allAliveByHash();
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, List<Bookmark>> e : byHash.entrySet()) {
            String fileHash = e.getKey();
            LocalProgressRepository.Entry progress = progressRepo.get(fileHash);
            String displayName;
            String filePath;
            int totalChars;
            if (progress != null) {
                File f = new File(progress.filePath);
                displayName = stripTxt(f.getName());
                filePath = progress.filePath;
                totalChars = progress.totalChars;
            } else {
                displayName = "(로컬에 파일 없음)";
                filePath = null;
                totalChars = 0;
            }
            for (Bookmark b : e.getValue()) {
                items.add(new Item(fileHash, displayName, filePath, b, totalChars));
            }
        }
        items.sort(Comparator.comparingLong((Item it) -> it.bookmark.createdAt).reversed());
        adapter.submit(items);

        boolean empty = items.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);

        // 부모에게 개수 통지 — 섹션 타이틀 옆 괄호 표시.
        if (getParentFragment() instanceof FavoritesFragment) {
            ((FavoritesFragment) getParentFragment()).setMyBookmarkCount(items.size());
        }
    }

    private void openBookmark(Item item) {
        if (item.filePath == null) {
            Toast.makeText(requireContext(),
                    "이 기기에 해당 책 파일이 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(requireActivity() instanceof BookListActivity)) return;
        ((BookListActivity) requireActivity())
                .openBook(item.filePath, item.bookmark.charOffset, /*skipConflict*/ true);
    }

    private void showDeleteMenu(View anchor, Item item) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenuInflater().inflate(R.menu.menu_item_delete, menu.getMenu());
        menu.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == R.id.action_delete) {
                if (bmRepo == null && getActivity() instanceof BookListActivity) {
                    bmRepo = ((BookListActivity) getActivity()).getBookmarksRepo();
                }
                if (bmRepo == null) return true;
                boolean removed = bmRepo.deleteById(item.fileHash, item.bookmark.id);
                if (removed) {
                    Toast.makeText(requireContext(),
                            R.string.bookmark_deleted_toast,
                            Toast.LENGTH_SHORT).show();
                }
                refresh();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private static String stripTxt(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
    }

    // ── Data model ──────────────────────────────────────────────────────────

    private static class Item {
        final String fileHash;
        final String bookTitle;
        /** null if this device doesn't have the book file. */
        final String filePath;
        final Bookmark bookmark;
        /** 0 if totalChars unknown (로컬에 진행 기록 없음). */
        final int totalChars;
        Item(String fileHash, String bookTitle, String filePath, Bookmark bookmark, int totalChars) {
            this.fileHash = fileHash;
            this.bookTitle = bookTitle;
            this.filePath = filePath;
            this.bookmark = bookmark;
            this.totalChars = totalChars;
        }
        int percentRead() {
            if (totalChars <= 0) return -1;
            return (int) (bookmark.charOffset * 100L / totalChars);
        }
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final SimpleDateFormat fmt = new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault());
        private List<Item> items = new ArrayList<>();

        void submit(List<Item> list) { this.items = list; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_my_bookmark, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Item item = items.get(pos);
            h.tvBook.setText(item.bookTitle);
            h.tvPreview.setText(item.bookmark.preview.isEmpty()
                    ? h.itemView.getContext().getString(R.string.bookmark_no_preview)
                    : item.bookmark.preview);
            String timeStr = fmt.format(new Date(item.bookmark.createdAt));
            int pct = item.percentRead();
            h.tvCreated.setText(pct >= 0 ? timeStr + " (" + pct + "%)" : timeStr);

            int textColor = ThemePrefs.textColor(h.itemView.getContext());
            h.tvBook.setTextColor(textColor);
            h.tvPreview.setTextColor(textColor);
            h.tvCreated.setTextColor(textColor);

            float alpha = item.filePath != null ? 1f : 0.4f;
            h.tvBook.setAlpha(alpha);
            h.tvPreview.setAlpha(alpha);
            h.tvCreated.setAlpha(alpha);

            h.itemView.setOnClickListener(v -> openBookmark(item));
            h.itemView.setOnLongClickListener(v -> { showDeleteMenu(v, item); return true; });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvBook;
            final TextView tvPreview;
            final TextView tvCreated;
            VH(@NonNull View v) {
                super(v);
                tvBook = v.findViewById(R.id.tv_book_title);
                tvPreview = v.findViewById(R.id.tv_preview);
                tvCreated = v.findViewById(R.id.tv_created_at);
            }
        }
    }
}
