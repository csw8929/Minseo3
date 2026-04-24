package com.example.minseo3;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * {@link FileUtils#decodeAuto(byte[])} 테스트 — BOM / UTF-8 / CP949 자동 감지.
 */
public class FileUtilsDecodeTest {

    private static final String SAMPLE = "안녕하세요. 한국 소설입니다.\n둘째 줄.";

    @Test public void utf8_noBom_decodesCorrectly() {
        byte[] bytes = SAMPLE.getBytes(StandardCharsets.UTF_8);
        assertEquals(SAMPLE, FileUtils.decodeAuto(bytes));
    }

    @Test public void utf8_withBom_stripsBomAndDecodes() {
        byte[] payload = SAMPLE.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[payload.length + 3];
        bytes[0] = (byte) 0xEF; bytes[1] = (byte) 0xBB; bytes[2] = (byte) 0xBF;
        System.arraycopy(payload, 0, bytes, 3, payload.length);
        assertEquals(SAMPLE, FileUtils.decodeAuto(bytes));
    }

    @Test public void cp949_decodesAsKoreanText() {
        byte[] bytes = SAMPLE.getBytes(Charset.forName("x-windows-949"));
        // CP949 바이트가 UTF-8 strict 로는 malformed 이라 폴백 경로가 발동해 제대로 디코드 되어야 함.
        assertEquals(SAMPLE, FileUtils.decodeAuto(bytes));
    }

    @Test public void utf16Be_withBom_decodesCorrectly() {
        byte[] payload = SAMPLE.getBytes(StandardCharsets.UTF_16BE);
        byte[] bytes = new byte[payload.length + 2];
        bytes[0] = (byte) 0xFE; bytes[1] = (byte) 0xFF;
        System.arraycopy(payload, 0, bytes, 2, payload.length);
        assertEquals(SAMPLE, FileUtils.decodeAuto(bytes));
    }

    @Test public void utf16Le_withBom_decodesCorrectly() {
        byte[] payload = SAMPLE.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[payload.length + 2];
        bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xFE;
        System.arraycopy(payload, 0, bytes, 2, payload.length);
        assertEquals(SAMPLE, FileUtils.decodeAuto(bytes));
    }

    @Test public void asciiOnly_decodesAsUtf8() {
        String ascii = "Hello world.\nLine two.";
        byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
        assertEquals(ascii, FileUtils.decodeAuto(bytes));
    }

    @Test public void empty_returnsEmpty() {
        assertEquals("", FileUtils.decodeAuto(new byte[0]));
    }
}
