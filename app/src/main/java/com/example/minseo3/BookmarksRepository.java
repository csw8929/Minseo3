package com.example.minseo3;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local bookmark storage — a single bookmarks.json in getFilesDir() groups
 * bookmarks by fileHash. Matches the "all in one file" pattern of
 * {@link LocalProgressRepository}.
 *
 * Conflict resolution lives in a sibling class (Phase 2). This repository is
 * responsible for local persistence, an in-memory sorted offset cache per book
 * for fast star-icon lookups, and change notifications (onChanged listener).
 */
public class BookmarksRepository {

    private static final String FILE_NAME = "bookmarks.json";
    private static final int SCHEMA_VERSION = 1;

    private final File storageFile;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // fileHash -> list of bookmarks (both alive and tombstoned).
    private final Map<String, List<Bookmark>> byHash = new LinkedHashMap<>();

    // fileHash -> sorted long[] of alive bookmarks' charOffsets.
    // Rebuilt lazily; invalidated by every mutation / replaceAll.
    private final Map<String, long[]> aliveOffsetCache = new LinkedHashMap<>();

    /**
     * Multi-listener — 리더와 즐겨찾기 탭이 동시에 mutation 을 관찰해야 하므로
     * 단일 Runnable 가 아닌 리스트. 중복 등록은 무시 (같은 인스턴스 재 add 시 no-op).
     */
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public BookmarksRepository(Context context) {
        this.storageFile = new File(context.getFilesDir(), FILE_NAME);
        load();
    }

    /** mutation 시 main 스레드로 posted 되는 콜백 등록. 중복 add 는 무시. */
    public void addChangedListener(Runnable r) {
        if (r != null) listeners.addIfAbsent(r);
    }

    public void removeChangedListener(Runnable r) {
        if (r != null) listeners.remove(r);
    }

    /**
     * Add if no alive bookmark covers this page, otherwise tombstone the
     * covering one. Returns true if the page is bookmarked AFTER the call.
     */
    public synchronized boolean toggleAtPage(String fileHash, int pageStart, int pageEnd,
                                             String text, String deviceId) {
        long now = System.currentTimeMillis();
        List<Bookmark> list = byHash.get(fileHash);
        if (list == null) {
            list = new ArrayList<>();
            byHash.put(fileHash, list);
        }

        for (int i = 0; i < list.size(); i++) {
            Bookmark b = list.get(i);
            if (b.isAlive() && b.charOffset >= pageStart && b.charOffset < pageEnd) {
                list.set(i, b.withDeletedAt(now));
                invalidateAndPersist(fileHash);
                return false;
            }
        }

        // No alive in range → insert or revive
        Bookmark fresh = Bookmark.createAtPage(fileHash, pageStart, text, deviceId, now);
        int sameIdIdx = indexOfId(list, fresh.id);
        if (sameIdIdx >= 0) {
            // Revive the tombstoned record — preserve original preview if present.
            Bookmark old = list.get(sameIdIdx);
            Bookmark revived = new Bookmark(
                    old.id, deviceId, old.charOffset,
                    old.preview.isEmpty() ? fresh.preview : old.preview,
                    now, null);
            list.set(sameIdIdx, revived);
        } else {
            list.add(fresh);
        }
        invalidateAndPersist(fileHash);
        return true;
    }

    /** Soft-delete by id (explicit list delete). Returns true if something changed. */
    public synchronized boolean deleteById(String fileHash, String id) {
        List<Bookmark> list = byHash.get(fileHash);
        if (list == null) return false;
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            Bookmark b = list.get(i);
            if (b.id.equals(id) && b.isAlive()) {
                list.set(i, b.withDeletedAt(now));
                invalidateAndPersist(fileHash);
                return true;
            }
        }
        return false;
    }

    /** Undo a soft-delete (e.g., Snackbar undo). */
    public synchronized boolean undeleteById(String fileHash, String id, String deviceId) {
        List<Bookmark> list = byHash.get(fileHash);
        if (list == null) return false;
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            Bookmark b = list.get(i);
            if (b.id.equals(id) && !b.isAlive()) {
                list.set(i, b.revived(deviceId, now));
                invalidateAndPersist(fileHash);
                return true;
            }
        }
        return false;
    }

    /** True iff any alive bookmark's charOffset ∈ [pageStart, pageEnd). O(log n). */
    public synchronized boolean anyInRange(String fileHash, int pageStart, int pageEnd) {
        long[] offs = ensureCache(fileHash);
        if (offs.length == 0) return false;
        int lo = 0, hi = offs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (offs[mid] < pageStart) lo = mid + 1;
            else hi = mid;
        }
        return lo < offs.length && offs[lo] < pageEnd;
    }

    /** Alive bookmarks for this book, sorted by createdAt descending. */
    public synchronized List<Bookmark> getAliveSortedByRecent(String fileHash) {
        List<Bookmark> list = byHash.get(fileHash);
        if (list == null) return Collections.emptyList();
        List<Bookmark> alive = new ArrayList<>();
        for (Bookmark b : list) if (b.isAlive()) alive.add(b);
        alive.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return alive;
    }

    /** Replace all bookmarks for one book (used by Phase 2 pull-merge flow). */
    public synchronized void replaceAll(String fileHash, List<Bookmark> merged) {
        byHash.put(fileHash, new ArrayList<>(merged));
        invalidateAndPersist(fileHash);
    }

    /** Full list (alive + tombstone) for one book — Phase 2 push snapshot. */
    public synchronized List<Bookmark> getAll(String fileHash) {
        List<Bookmark> list = byHash.get(fileHash);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    /** All alive bookmarks across all books (used by Phase 3 '내 북마크' tab). */
    public synchronized Map<String, List<Bookmark>> allAliveByHash() {
        Map<String, List<Bookmark>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Bookmark>> e : byHash.entrySet()) {
            List<Bookmark> alive = new ArrayList<>();
            for (Bookmark b : e.getValue()) if (b.isAlive()) alive.add(b);
            if (!alive.isEmpty()) out.put(e.getKey(), alive);
        }
        return out;
    }

    // ── internals ───────────────────────────────────────────────────────────

    private int indexOfId(List<Bookmark> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
        return -1;
    }

    private long[] ensureCache(String fileHash) {
        long[] cached = aliveOffsetCache.get(fileHash);
        if (cached != null) return cached;
        List<Bookmark> list = byHash.get(fileHash);
        if (list == null) { aliveOffsetCache.put(fileHash, new long[0]); return new long[0]; }
        List<Long> alive = new ArrayList<>(list.size());
        for (Bookmark b : list) if (b.isAlive()) alive.add((long) b.charOffset);
        Collections.sort(alive);
        long[] arr = new long[alive.size()];
        for (int i = 0; i < alive.size(); i++) arr[i] = alive.get(i);
        aliveOffsetCache.put(fileHash, arr);
        return arr;
    }

    private void invalidateAndPersist(String fileHash) {
        aliveOffsetCache.remove(fileHash);
        persist();
        fireChanged();
    }

    private void fireChanged() {
        // snapshot iteration: CopyOnWriteArrayList 이라 concurrent mutation safe.
        for (Runnable r : listeners) mainHandler.post(r);
    }

    private void load() {
        if (!storageFile.exists()) return;
        try (FileReader reader = new FileReader(storageFile)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
            JSONObject root = new JSONObject(sb.toString());
            // Lenient: version > 1 → still read whatever fields we recognize.
            JSONObject books = root.optJSONObject("byFileHash");
            if (books == null) return;
            Iterator<String> keys = books.keys();
            while (keys.hasNext()) {
                String hash = keys.next();
                JSONArray arr = books.optJSONArray(hash);
                if (arr == null) continue;
                List<Bookmark> list = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        list.add(Bookmark.fromJson(arr.getJSONObject(i)));
                    } catch (JSONException ignored) {
                        // skip unparseable records, keep the rest
                    }
                }
                byHash.put(hash, list);
            }
        } catch (Exception ignored) {
            // corrupt file — start fresh (matches LocalProgressRepository behavior)
            byHash.clear();
        }
    }

    private void persist() {
        try (FileWriter w = new FileWriter(storageFile)) {
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            JSONObject books = new JSONObject();
            for (Map.Entry<String, List<Bookmark>> e : byHash.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Bookmark b : e.getValue()) arr.put(b.toJson());
                books.put(e.getKey(), arr);
            }
            root.put("byFileHash", books);
            w.write(root.toString());
        } catch (Exception ignored) {
            // persist failure is not fatal; next mutation retries.
        }
    }
}
