package com.example.minseo3;

import android.content.Context;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Persists page-boundary offsets so re-opening a book at the same font size and
 * screen geometry skips the full StaticLayout rebuild.
 *
 * Cache key = (fileHash, textSizeSp, widthPx, heightPx). Anything else that
 * affects layout (line spacing, font face) is currently constant.
 */
public class PaginationCache {

    private final File cacheDir;

    public PaginationCache(Context context) {
        cacheDir = new File(context.getCacheDir(), "pagination");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public int[] load(String fileHash, int sizeSp, int widthPx, int heightPx) {
        File f = file(fileHash, sizeSp, widthPx, heightPx);
        if (!f.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
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
            out.writeInt(offsets.length);
            for (int o : offsets) out.writeInt(o);
        } catch (Exception ignored) {}
    }

    private File file(String fileHash, int sizeSp, int widthPx, int heightPx) {
        return new File(cacheDir, fileHash + "_" + sizeSp + "_" + widthPx + "x" + heightPx + ".bin");
    }
}
