package com.example.minseo3;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LocalProgressRepository {

    private static final String FILE_NAME = "reading_progress.json";
    private final File progressFile;
    // LinkedHashMap preserves insertion order (most recently updated first after re-insert)
    private final Map<String, Entry> cache = new LinkedHashMap<>();

    public LocalProgressRepository(Context context) {
        progressFile = new File(context.getFilesDir(), FILE_NAME);
        load();
    }

    public synchronized void save(String fileHash, String filePath, int charOffset, int totalChars) {
        cache.remove(fileHash); // remove then re-insert to move to end
        cache.put(fileHash, new Entry(fileHash, filePath, charOffset, totalChars, System.currentTimeMillis()));
        persist();
    }

    public synchronized Entry get(String fileHash) {
        return cache.get(fileHash);
    }

    /** Returns all entries sorted by lastRead descending (most recent first). */
    public synchronized List<Entry> getAllSortedByRecent() {
        List<Entry> list = new ArrayList<>(cache.values());
        list.sort((a, b) -> Long.compare(b.lastRead, a.lastRead));
        return list;
    }

    private void load() {
        if (!progressFile.exists()) return;
        try (FileReader reader = new FileReader(progressFile)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Entry e = new Entry(
                        obj.getString("fileHash"),
                        obj.getString("filePath"),
                        obj.getInt("charOffset"),
                        obj.getInt("totalChars"),
                        obj.getLong("lastRead"));
                cache.put(e.fileHash, e);
            }
        } catch (Exception ignored) {
            // corrupt file — start fresh
        }
    }

    private void persist() {
        try (FileWriter writer = new FileWriter(progressFile)) {
            JSONArray arr = new JSONArray();
            for (Entry e : cache.values()) {
                JSONObject obj = new JSONObject();
                obj.put("fileHash", e.fileHash);
                obj.put("filePath", e.filePath);
                obj.put("charOffset", e.charOffset);
                obj.put("totalChars", e.totalChars);
                obj.put("lastRead", e.lastRead);
                arr.put(obj);
            }
            writer.write(arr.toString());
        } catch (Exception ignored) {}
    }

    public static class Entry {
        public final String fileHash;
        public final String filePath;
        public final int charOffset;
        public final int totalChars;
        public final long lastRead;

        Entry(String fileHash, String filePath, int charOffset, int totalChars, long lastRead) {
            this.fileHash = fileHash;
            this.filePath = filePath;
            this.charOffset = charOffset;
            this.totalChars = totalChars;
            this.lastRead = lastRead;
        }

        public int percentRead() {
            if (totalChars <= 0) return 0;
            return (int) (charOffset * 100L / totalChars);
        }

        public String lastReadFormatted() {
            return new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date(lastRead));
        }
    }
}
