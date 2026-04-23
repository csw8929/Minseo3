package com.example.minseo3;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.minseo3.nas.RemoteProgressRepository;
import com.example.minseo3.nas.RemotePosition;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** "NAS" 탭 — 모든 기기에서 올라온 pos_*.json 목록. */
public class NasHistoryFragment extends Fragment {

    private RecyclerView recyclerView;
    private ProgressBar progress;
    private TextView tvStatus;
    private NasAdapter adapter;
    private NasSyncManager nas;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_nas_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        nas = new NasSyncManager(requireContext());
        recyclerView = view.findViewById(R.id.recycler_view);
        progress = view.findViewById(R.id.progress);
        tvStatus = view.findViewById(R.id.tv_status);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));
        adapter = new NasAdapter();
        recyclerView.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!nas.isEnabled()) {
            showMessage("NAS 동기화가 꺼져 있습니다.\n우측 상단 메뉴 → NAS 설정에서 활성화하세요.");
            return;
        }
        if (!nas.isConnected()) {
            // 낙관적 시도 — enabled 이면 isConnected 가 true 였다가 실패하면 false. 재시도.
            showLoading();
        } else {
            showLoading();
        }

        nas.fetchAll(new RemoteProgressRepository.Callback<Map<String, RemotePosition>>() {
            @Override public void onResult(Map<String, RemotePosition> value) {
                mainHandler.post(() -> onFetched(value));
            }
            @Override public void onError(String message) {
                mainHandler.post(() -> showMessage("NAS 연결 실패: " + message));
            }
        });
    }

    private void onFetched(Map<String, RemotePosition> map) {
        String myDeviceId = nas.deviceId();
        List<NasItem> items = new ArrayList<>();
        for (Map.Entry<String, RemotePosition> e : map.entrySet()) {
            RemotePosition p = e.getValue();
            // 이 섹션은 "다른 단말" 전용 — 내 기기에서 올린 항목은 제외한다.
            if (myDeviceId != null && myDeviceId.equals(p.deviceId)) continue;
            File local = FileUtils.findLocalByNameAndSize(p.fileName, p.fileSize);
            items.add(new NasItem(e.getKey(), p, local));
        }
        items.sort(Comparator.comparingLong((NasItem it) -> it.pos.lastUpdatedEpoch).reversed());
        adapter.setItems(items);

        if (items.isEmpty()) {
            showMessage("다른 단말에서 올린 읽기 기록이 없습니다.");
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            tvStatus.setVisibility(View.GONE);
        }
    }

    private void showLoading() {
        progress.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);
    }

    private void showMessage(String msg) {
        progress.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);
        tvStatus.setText(msg);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void showDeleteMenu(View anchor, NasItem item) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenuInflater().inflate(R.menu.menu_item_delete, menu.getMenu());
        menu.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == R.id.action_delete) {
                nas.deletePosition(item.fileHash, new RemoteProgressRepository.Callback<Void>() {
                    @Override public void onResult(Void v) {
                        mainHandler.post(() -> refresh());
                    }
                    @Override public void onError(String message) {
                        mainHandler.post(() -> Toast.makeText(requireContext(),
                                "삭제 실패: " + message, Toast.LENGTH_SHORT).show());
                    }
                });
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void openBook(NasItem item) {
        if (item.localFile == null) {
            Toast.makeText(requireContext(), "이 기기에 '" + item.pos.fileName + "' 파일이 없습니다",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(requireContext(), ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, item.localFile.getAbsolutePath());
        intent.putExtra(ReaderActivity.EXTRA_CHAR_OFFSET, item.pos.charOffset);
        // NAS 탭에서 진입 — 이미 NAS offset 을 가지고 있으므로 충돌 해결 생략.
        intent.putExtra(ReaderActivity.EXTRA_SKIP_CONFLICT_RESOLVE, true);
        startActivity(intent);
    }

    // ── Data model ──────────────────────────────────────────────────────────

    private static class NasItem {
        final String fileHash;
        final RemotePosition pos;
        final File localFile; // null if this device doesn't have the file
        NasItem(String hash, RemotePosition pos, File local) {
            this.fileHash = hash; this.pos = pos; this.localFile = local;
        }
        boolean isActive() { return localFile != null; }
    }

    // ── Adapter ─────────────────────────────────────────────────────────────

    private class NasAdapter extends RecyclerView.Adapter<NasAdapter.VH> {
        private List<NasItem> items = new ArrayList<>();
        void setItems(List<NasItem> list) { this.items = list; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_nas_history, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            NasItem item = items.get(pos);
            String title = stripTxt(item.pos.fileName);
            int percent = item.pos.totalChars > 0
                    ? (int) (item.pos.charOffset * 100L / item.pos.totalChars) : 0;

            h.tvTitle.setText(title);
            String ago = DateUtils.getRelativeTimeSpanString(
                    item.pos.lastUpdatedEpoch,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            String deviceShort = item.pos.deviceId.length() >= 4
                    ? item.pos.deviceId.substring(0, 4) : item.pos.deviceId;
            String suffix = item.isActive() ? "" : " · 이 기기에 파일 없음";
            h.tvInfo.setText(percent + "% · 기기 " + deviceShort + " · " + ago + suffix);
            h.progressBar.setProgress(percent);

            float alpha = item.isActive() ? 1f : 0.4f;
            h.tvTitle.setAlpha(alpha);
            h.tvInfo.setAlpha(alpha);
            h.progressBar.setAlpha(alpha);
            h.itemView.setEnabled(true); // 비활성도 탭 가능 — 안내 토스트 띄움
            h.itemView.setOnClickListener(v -> openBook(item));
            h.itemView.setOnLongClickListener(v -> { showDeleteMenu(v, item); return true; });
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

    private static String stripTxt(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase();
        return lower.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
    }
}
