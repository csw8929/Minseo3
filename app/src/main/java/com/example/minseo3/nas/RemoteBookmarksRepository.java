package com.example.minseo3.nas;

import com.example.minseo3.Bookmark;

import java.util.List;

/**
 * 원격 북마크 저장소 추상화. v1 구현은 {@link SynologyBookmarksRepository}.
 * 테스트는 {@link FakeRemoteBookmarksRepository} 사용.
 *
 * 파일 단위: /{.minseo}/bm_{fileHash}.json 하나에 해당 책의 모든 북마크 (alive + tombstone)
 * flat 리스트로 저장. {@link RemoteProgressRepository} 의 pos_ 파일과 분리된 네임스페이스.
 */
public interface RemoteBookmarksRepository {

    interface Callback<T> {
        void onResult(T value);
        void onError(String message);
    }

    /** 호출자가 로컬에 보관한 북마크 전체를 덮어쓰기. 빈 리스트면 bookmarks 필드 빈 배열 저장. */
    void push(String fileHash, List<Bookmark> bookmarks, Callback<Void> cb);

    /** 한 권의 북마크 리스트. 파일 없으면 빈 리스트, 파싱 실패도 빈 리스트로 lenient. */
    void fetchOne(String fileHash, Callback<List<Bookmark>> cb);
}
