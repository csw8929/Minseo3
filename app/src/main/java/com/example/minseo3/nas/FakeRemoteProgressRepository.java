package com.example.minseo3.nas;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 인메모리 저장소. 테스트와 Phase 1 기본값으로 사용.
 * 콜백은 호출 스레드에서 즉시 실행 (비동기 시뮬레이션 없음).
 */
public class FakeRemoteProgressRepository implements RemoteProgressRepository {

    private final Map<String, RemotePosition> store = new LinkedHashMap<>();
    private final String deviceId;
    private boolean nextCallFails = false;
    private String failMessage = "fake failure";

    public FakeRemoteProgressRepository() { this("fake-device"); }

    public FakeRemoteProgressRepository(String deviceId) {
        this.deviceId = deviceId;
    }

    /** 테스트 헬퍼: 다음 push/fetchOne/fetchAll 호출이 onError를 발동하게 한다. */
    public void failNextCall(String message) {
        this.nextCallFails = true;
        this.failMessage = message;
    }

    @Override public String deviceId() { return deviceId; }

    @Override
    public void push(String fileHash, RemotePosition pos, Callback<Void> cb) {
        if (consumeFailure()) { cb.onError(failMessage); return; }
        store.put(fileHash, pos);
        cb.onResult(null);
    }

    @Override
    public void fetchOne(String fileHash, Callback<RemotePosition> cb) {
        if (consumeFailure()) { cb.onError(failMessage); return; }
        cb.onResult(store.get(fileHash));
    }

    @Override
    public void fetchAll(Callback<Map<String, RemotePosition>> cb) {
        if (consumeFailure()) { cb.onError(failMessage); return; }
        cb.onResult(new HashMap<>(store));
    }

    @Override
    public void delete(String fileHash, Callback<Void> cb) {
        if (consumeFailure()) { cb.onError(failMessage); return; }
        store.remove(fileHash);
        cb.onResult(null);
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
