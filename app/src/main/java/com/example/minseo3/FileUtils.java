package com.example.minseo3;

import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileUtils {

    public static File getNovelDir() {
        return new File(Environment.getExternalStorageDirectory(), "소설");
    }

    /** Lists subdirectories and .txt files in {@code dir}. Directories sort first. */
    public static List<File> listEntries(File dir) {
        List<File> result = new ArrayList<>();
        if (dir == null || !dir.exists() || !dir.isDirectory()) return result;
        File[] files = dir.listFiles(f ->
                f.isDirectory() || (f.isFile() && f.getName().toLowerCase().endsWith(".txt")));
        if (files == null) return result;
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        result.addAll(Arrays.asList(files));
        return result;
    }

    /** Hash key: sha256(filename + filesize). Lightweight, consistent across devices. */
    public static String computeHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = file.getName() + file.length();
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return file.getName().replaceAll("[^a-zA-Z0-9]", "_");
        }
    }

    public static String readTextFile(File file) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder((int) file.length());
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        }
    }

    /**
     * NAS 탭에서 활성/비활성 판정용. /소설/ 트리를 재귀 탐색해 같은 파일명 + 같은
     * 크기 의 .txt 를 찾는다 (중첩 폴더 지원).
     */
    public static File findLocalByNameAndSize(String fileName, long fileSize) {
        if (fileName == null) return null;
        return findRecursive(getNovelDir(), fileName, fileSize);
    }

    private static File findRecursive(File dir, String fileName, long fileSize) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(fileName) && f.length() == fileSize
                    && fileName.toLowerCase().endsWith(".txt")) {
                return f;
            }
            if (f.isDirectory()) {
                File hit = findRecursive(f, fileName, fileSize);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    public static String displayName(File file) {
        String name = file.getName();
        if (name.toLowerCase().endsWith(".txt")) name = name.substring(0, name.length() - 4);
        return name;
    }
}
