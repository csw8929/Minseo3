package com.example.minseo3;

import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
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

    /**
     * .txt 를 디코드. 한국 소설은 보통 UTF-8 아니면 CP949 (EUC-KR 수퍼셋) 이라
     * 자동 감지 후 읽는다.
     *
     * 절차:
     *   1. 전체 바이트 로드
     *   2. BOM 검사 — UTF-8 BOM(EF BB BF) / UTF-16 BOM(FE FF / FF FE) 우선
     *   3. BOM 없으면 strict UTF-8 디코드 시도. 성공하면 UTF-8, malformed 발생하면 CP949 로 폴백.
     *
     * CP949(Windows-949) 는 EUC-KR 의 Microsoft 확장으로 거의 모든 한국 .txt 가 이 안에 들어옴.
     */
    public static String readTextFile(File file) throws Exception {
        byte[] bytes = readAllBytes(file);
        return decodeAuto(bytes);
    }

    private static byte[] readAllBytes(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(file.length(), Integer.MAX_VALUE));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** 바이트 → String. BOM 우선, 없으면 UTF-8 시도 후 실패 시 CP949. */
    static String decodeAuto(byte[] bytes) {
        // UTF-8 BOM
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        // UTF-16 BE BOM
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        // UTF-16 LE BOM
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        // Strict UTF-8 시도
        if (isValidUtf8(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        // 한국 .txt 폴백
        try {
            return new String(bytes, Charset.forName("x-windows-949"));
        } catch (Exception e) {
            // Charset 이름이 기기에 없으면 EUC-KR 로 한 번 더 시도, 그래도 안되면 UTF-8 (깨짐 감수).
            try {
                return new String(bytes, Charset.forName("EUC-KR"));
            } catch (Exception ignored) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    private static boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
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
