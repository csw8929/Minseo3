package com.example.minseo3;

import android.content.Context;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * Persists page-boundary offsets so re-opening a book at the same font size and
 * screen geometry skips the full StaticLayout rebuild.
 *
 * Cache key = (fileHash, textSizeSp, widthPx, heightPx). Anything else that
 * affects layout (line spacing, font face) participates via CACHE_VERSION:
 * bump it whenever those constants change to invalidate stale entries.
 */
public class PaginationCache {

    // Bump when LINE_SPACING_*, font face, or any layout-affecting constant changes.
    // v2: FileUtils 가 CP949/UTF-16 자동 감지로 변경 — 이전까지 UTF-8 하드코딩이어서
    //     non-UTF8 파일은 replacement char (�) 로 읽혀 char offset 이 왜곡됨.
    //     감지 후엔 텍스트 길이가 달라지므로 기존 캐시 전부 무효화.
    private static final int CACHE_VERSION = 2;
    private static final int MAX_ENTRIES = 200;

    private final File cacheDir;

    public PaginationCache(Context context) {
        cacheDir = new File(context.getCacheDir(), "pagination");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public int[] load(String fileHash, int sizeSp, int widthPx, int heightPx) {
        File f = file(fileHash, sizeSp, widthPx, heightPx);
        if (!f.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            int version = in.readInt();
            if (version != CACHE_VERSION) return null;
            int count = in.readInt();
            if (count < 1 || count > 2_000_000) return null;
            int[] offsets = new int[count];
            for (int i = 0; i < count; i++) offsets[i] = in.readInt();
            return offsets;
        } catch (Exception e) {
            return null;
        }
    }

    public void save(String fileHash, int sizeSp, int widthPx, int heightPx, int[] offsets) {
        try (DataOutputStream out = new DataOutputStream(
                new FileOutputStream(file(fileHash, sizeSp, widthPx, heightPx)))) {
            out.writeInt(CACHE_VERSION);
            out.writeInt(offsets.length);
            for (int o : offsets) out.writeInt(o);
        } catch (Exception ignored) {}
        evictIfNeeded();
    }

    /** Keep cacheDir bounded — delete oldest entries by mtime when over MAX_ENTRIES. */
    private void evictIfNeeded() {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length <= MAX_ENTRIES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int toDelete = files.length - MAX_ENTRIES;
        for (int i = 0; i < toDelete; i++) files[i].delete();
    }

    private File file(String fileHash, int sizeSp, int widthPx, int heightPx) {
        return new File(cacheDir, fileHash + "_" + sizeSp + "_" + widthPx + "x" + heightPx + ".bin");
    }
}
