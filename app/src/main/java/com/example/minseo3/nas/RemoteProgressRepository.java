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

    /**
     * pos_{fileHash}.json 파일을 NAS에서 삭제. 파일 없어도 성공 간주 (멱등).
     * "다른 단말 진행" 리스트에서 사용자가 특정 기록을 치우고 싶을 때 사용.
     */
    void delete(String fileHash, Callback<Void> cb);
}
