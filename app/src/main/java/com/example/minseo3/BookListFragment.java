package com.example.minseo3;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "내 책" 탭 — 로컬 /소설/ 폴더 트리. 중첩 폴더 네비게이션 지원.
 * Activity 의 뒤로가기가 {@link #goUpIfPossible()} 을 먼저 호출해 폴더 스택을 소비.
 */
public class BookListFragment extends Fragment {

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private LocalProgressRepository progressRepo;
    private BookAdapter adapter;
    private File currentDir;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_book_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        progressRepo = new LocalProgressRepository(requireContext());
        recyclerView = view.findViewById(R.id.recycler_view);
        tvEmpty = view.findViewById(R.id.tv_empty);
        currentDir = FileUtils.getNovelDir();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));
        adapter = new BookAdapter();
        recyclerView.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    /** Activity 가 permission 획득 후 호출. */
    public void reload() {
        if (getView() == null) return;
        if (!(requireActivity() instanceof BookListActivity)) return;
        if (!((BookListActivity) requireActivity()).hasStoragePermissionPublic()) {
            tvEmpty.setText("/소설/ 폴더 접근 권한이 필요합니다.");
            tvEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            return;
        }

        List<File> files = FileUtils.listEntries(currentDir);
        List<BookItem> items = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory()) {
                items.add(new BookItem(f, null, true));
            } else {
                String hash = FileUtils.computeHash(f);
                LocalProgressRepository.Entry entry = progressRepo.get(hash);
                items.add(new BookItem(f, entry, false));
            }
        }
        adapter.setItems(items);

        boolean empty = items.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            tvEmpty.setText(isAtRoot()
                    ? "/소설/ 폴더에 텍스트 파일이 없습니다."
                    : "이 폴더가 비어있습니다.");
        }
    }

    /** 상위 폴더로 한 단계 올라가야 하면 올라가고 true. 루트면 false. */
    public boolean goUpIfPossible() {
        if (isAtRoot()) return false;
        currentDir = currentDir.getParentFile();
        reload();
        return true;
    }

    private boolean isAtRoot() {
        return currentDir == null || currentDir.equals(FileUtils.getNovelDir());
    }

    private void openBook(BookItem item) {
        if (!(requireActivity() instanceof BookListActivity)) return;
        BookListActivity host = (BookListActivity) requireActivity();
        int startOffset = (item.entry != null) ? item.entry.charOffset : 0;
        host.openBook(item.file.getAbsolutePath(), startOffset, /*skipConflict*/ false);
    }

    private void openFolder(BookItem item) {
        currentDir = item.file;
        reload();
    }

    static class BookItem {
        final File file;
        final LocalProgressRepository.Entry entry;
        final boolean isDirectory;
        BookItem(File file, LocalProgressRepository.Entry entry, boolean isDirectory) {
            this.file = file; this.entry = entry; this.isDirectory = isDirectory;
        }
    }

    class BookAdapter extends RecyclerView.Adapter<BookAdapter.VH> {
        private List<BookItem> items = new ArrayList<>();
        void setItems(List<BookItem> list) { this.items = list; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_book, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BookItem item = items.get(pos);
            if (item.isDirectory) {
                h.tvTitle.setText("📁 " + item.file.getName());
                h.tvInfo.setText("폴더");
                h.progressBar.setVisibility(View.INVISIBLE);
                h.itemView.setOnClickListener(v -> openFolder(item));
                return;
            }
            h.tvTitle.setText(FileUtils.displayName(item.file));
            if (item.entry != null) {
                h.tvInfo.setText(item.entry.percentRead() + "% · " + item.entry.lastReadFormatted());
                h.progressBar.setProgress(item.entry.percentRead());
                h.progressBar.setVisibility(View.VISIBLE);
            } else {
                h.tvInfo.setText("읽지 않음");
                h.progressBar.setVisibility(View.INVISIBLE);
            }
            h.itemView.setOnClickListener(v -> openBook(item));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvInfo;
            ProgressBar progressBar;
            VH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tv_title);
                tvInfo = v.findViewById(R.id.tv_info);
                progressBar = v.findViewById(R.id.progress_bar);
            }
        }
    }
}
