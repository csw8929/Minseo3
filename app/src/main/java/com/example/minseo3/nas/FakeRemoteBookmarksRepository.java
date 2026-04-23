package com.example.minseo3.nas;

import com.example.minseo3.Bookmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 인메모리 북마크 저장소. 테스트와 Phase 1 fallback 으로 사용.
 * 콜백은 호출 스레드에서 즉시 실행 (비동기 시뮬레이션 없음).
 */
public class FakeRemoteBookmarksRepository implements RemoteBookmarksRepository {

    private final Map<String, List<Bookmark>> store = new LinkedHashMap<>();
    private boolean nextCallFails = false;
    private String failMessage = "fake failure";

    /** 테스트 헬퍼: 다음 push/fetchOne 호출이 onError 를 발동하게 한다. */
    public void failNextCall(String message) {
        this.nextCallFails = true;
        this.failMessage = message;
    }

    @Override
    public void push(String fileHash, List<Bookmark> bookmarks, Callback<Void> cb) {
        if (consumeFailure()) { cb.onError(failMessage); return; }
        store.put(fileHash, new ArrayList<>(bookmarks));
        cb.onResult(null);
    }

    @Override
    public void fetchOne(String fileHash, Callback<List<Bookmark>> cb) {
        if (consumeFailure()) { cb.onError(failMessage); return; }
        List<Bookmark> list = store.get(fileHash);
        cb.onResult(list == null ? Collections.<Bookmark>emptyList() : new ArrayList<>(list));
    }

    private boolean consumeFailure() {
        if (nextCallFails) {
            nextCallFails = false;
            return true;
        }
        return false;
    }

    public int size() { return store.size(); }
}
