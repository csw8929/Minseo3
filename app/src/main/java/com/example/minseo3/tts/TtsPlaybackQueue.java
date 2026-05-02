package com.example.minseo3.tts;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-scoped 싱글톤. ReaderFragment 와 TtsPlaybackService 가 공유하는 책 상태.
 *
 * 본문 텍스트, 페이지 분할 오프셋(int[]), 현재 페이지를 들고 있다.
 *   - load(): 새 책 진입 (다른 fileHash 면 currentPage=0 리셋)
 *   - replaceOffsets(): 같은 책의 재페이지네이션 — 글자 크기 변경, 콜드 오픈 prefix→full.
 *     char offset 기준으로 currentPage 재매핑.
 *   - clear(): 책 닫기, 메모리 해제
 *   - setCurrentPage(): 페이지 이동 통지 (서비스 onDone, 사용자 탭, MediaSession skip 모두 이 경로)
 *
 * 모든 listener 콜백은 main looper 로 dispatch — 등록자는 thread-safety 신경 안 써도 됨.
 */
public final class TtsPlaybackQueue {

    private static volatile TtsPlaybackQueue INSTANCE;

    public static TtsPlaybackQueue get() {
        TtsPlaybackQueue local = INSTANCE;
        if (local != null) return local;
        synchronized (TtsPlaybackQueue.class) {
            if (INSTANCE == null) INSTANCE = new TtsPlaybackQueue();
            return INSTANCE;
        }
    }

    public interface Listener {
        void onPageChanged(int page);
        void onLoadChanged();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private String text;            // null = 미로드
    private int[] pageStartOffsets;
    private int currentPage;
    private String fileHash;
    private String displayTitle;

    /**
     * 새 책 로드. 다른 fileHash 면 currentPage=0. 같은 fileHash 면 currentPage 유지.
     * 페이지 변경 통지는 안 함 — 호출자가 displayPage 통해 setCurrentPage 호출하도록.
     */
    public synchronized void load(String text, int[] offsets, String hash, String title) {
        boolean diffBook = (this.fileHash == null) || !this.fileHash.equals(hash);
        this.text = text;
        this.pageStartOffsets = offsets;
        this.fileHash = hash;
        this.displayTitle = title;
        if (diffBook) this.currentPage = 0;
        notifyLoadChanged();
    }

    /**
     * 같은 fileHash 의 재페이지네이션. char offset 기준으로 currentPage 재매핑.
     * 새 텍스트도 함께 갱신 (콜드 오픈 prefix→full 전환에서 필요).
     */
    public synchronized void replaceOffsets(int[] newOffsets, String newText) {
        if (newOffsets == null || newOffsets.length == 0) {
            this.pageStartOffsets = newOffsets;
            this.text = newText;
            notifyLoadChanged();
            return;
        }
        int currentCharOffset = currentPageStartOffsetLocked();
        this.pageStartOffsets = newOffsets;
        this.text = newText;
        // 새 offsets 에서 currentCharOffset 이 어느 페이지인지 — offsets 가 ascending 이라 마지막
        // "<= currentCharOffset" 인덱스가 답.
        int p = 0;
        for (int i = 0; i < newOffsets.length; i++) {
            if (newOffsets[i] > currentCharOffset) break;
            p = i;
        }
        this.currentPage = p;
        notifyLoadChanged();
        // 페이지 재매핑 결과 통지는 호출자(displayPage→setCurrentPage)가 담당.
    }

    public synchronized void clear() {
        this.text = null;
        this.pageStartOffsets = null;
        this.currentPage = 0;
        this.fileHash = null;
        this.displayTitle = null;
        notifyLoadChanged();
    }

    public synchronized void setCurrentPage(int page) {
        if (pageStartOffsets == null) return;
        int pc = pageStartOffsets.length;
        if (pc <= 0) return;
        if (page < 0) page = 0;
        if (page >= pc) page = pc - 1;
        if (page == this.currentPage) return;
        this.currentPage = page;
        notifyPageChanged();
    }

    public synchronized int getCurrentPage() { return currentPage; }
    public synchronized int pageCount() { return pageStartOffsets == null ? 0 : pageStartOffsets.length; }
    public synchronized boolean isLoaded() {
        return text != null && pageStartOffsets != null && pageStartOffsets.length > 0;
    }
    public synchronized String fileHash() { return fileHash; }
    public synchronized String displayTitle() { return displayTitle; }

    public synchronized String getPageText(int page) {
        if (text == null || pageStartOffsets == null) return "";
        if (page < 0 || page >= pageStartOffsets.length) return "";
        int start = pageStartOffsets[page];
        int end = (page + 1 < pageStartOffsets.length) ? pageStartOffsets[page + 1] : text.length();
        if (start < 0) start = 0;
        if (end > text.length()) end = text.length();
        if (end <= start) return "";
        return text.substring(start, end);
    }

    private int currentPageStartOffsetLocked() {
        if (pageStartOffsets == null || pageStartOffsets.length == 0) return 0;
        int p = currentPage;
        if (p < 0) p = 0;
        if (p >= pageStartOffsets.length) p = pageStartOffsets.length - 1;
        return pageStartOffsets[p];
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyPageChanged() {
        final int page = this.currentPage;
        mainHandler.post(() -> {
            for (Listener l : listeners) l.onPageChanged(page);
        });
    }

    private void notifyLoadChanged() {
        mainHandler.post(() -> {
            for (Listener l : listeners) l.onLoadChanged();
        });
    }

    private TtsPlaybackQueue() {}
}
