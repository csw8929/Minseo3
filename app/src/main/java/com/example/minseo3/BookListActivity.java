package com.example.minseo3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BookListActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 100;

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private LocalProgressRepository progressRepo;
    private BookAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_list);
        setTitle("소설 목록");

        progressRepo = new LocalProgressRepository(this);
        recyclerView = findViewById(R.id.recycler_view);
        tvEmpty = findViewById(R.id.tv_empty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        adapter = new BookAdapter();
        recyclerView.setAdapter(adapter);

        checkPermissionsAndLoad();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh list when returning from ReaderActivity (progress may have updated)
        if (hasStoragePermission()) loadBooks();
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void checkPermissionsAndLoad() {
        if (hasStoragePermission()) {
            loadBooks();
        } else {
            requestStoragePermission();
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
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
        if (code == REQ_STORAGE && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            loadBooks();
        } else {
            tvEmpty.setText("/소설/ 폴더 접근 권한이 필요합니다.");
            tvEmpty.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_STORAGE) {
            if (hasStoragePermission()) loadBooks();
            else {
                tvEmpty.setText("/소설/ 폴더 접근 권한이 필요합니다.");
                tvEmpty.setVisibility(View.VISIBLE);
            }
        }
    }

    // ── Book list ─────────────────────────────────────────────────────────────

    private void loadBooks() {
        List<File> files = FileUtils.listTextFiles();
        List<BookItem> items = new ArrayList<>();
        for (File f : files) {
            String hash = FileUtils.computeHash(f);
            LocalProgressRepository.Entry entry = progressRepo.get(hash);
            items.add(new BookItem(f, entry));
        }
        adapter.setItems(items);

        boolean empty = items.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) tvEmpty.setText("/소설/ 폴더에 텍스트 파일이 없습니다.");
    }

    private void openBook(BookItem item) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, item.file.getAbsolutePath());
        if (item.entry != null) intent.putExtra(ReaderActivity.EXTRA_CHAR_OFFSET, item.entry.charOffset);
        startActivity(intent);
    }

    // ── Data model ────────────────────────────────────────────────────────────

    static class BookItem {
        final File file;
        final LocalProgressRepository.Entry entry; // null if never opened
        BookItem(File file, LocalProgressRepository.Entry entry) {
            this.file = file; this.entry = entry;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

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
