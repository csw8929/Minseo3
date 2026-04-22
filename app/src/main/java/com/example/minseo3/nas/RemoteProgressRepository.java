package com.example.minseo3.nas;

import java.util.Map;

/**
 * 원격 위치 저장소 추상화. v1 구현은 {@link SynologyFileStationRepository} (Phase 2).
 * 테스트는 {@link FakeRemoteProgressRepository} 사용.
 */
public interface RemoteProgressRepository {

    interface Callback<T> {
        void onResult(T value);
        void onError(String message);
    }

    String deviceId();

    void push(String fileHash, RemotePosition pos, Callback<Void> cb);

    void fetchOne(String fileHash, Callback<RemotePosition> cb);

    void fetchAll(Callback<Map<String, RemotePosition>> cb);
}
