package com.example.minseo3.nas;

import com.example.minseo3.Bookmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 로컬과 원격 북마크 리스트를 union 한다. pos 쪽
 * {@link ConflictResolver} / {@link ConflictOutcome} 와는 독립적인 별개 클래스 —
 * pos 는 단일 값 arbitration (필요 시 다이얼로그), 북마크는 list union.
 *
 * 규칙:
 * 1. 동일 `id` 를 가진 두 레코드 → `max(createdAt, deletedAt ?? 0)` 더 큰 쪽 승리
 *    (deterministic id 덕분에 "같은 페이지" 는 항상 collide → 중복 제거 자동)
 * 2. 다른 id 면 둘 다 살림 (이종 기기 페이지네이션으로 다른 offset 이 생긴 경우)
 * 3. 승리한 레코드가 tombstone (`deletedAt != null`) 이어도 리스트에 남김 —
 *    이렇게 안 하면 "A 삭제 → B push(미삭제) → A pull union 이 살려냄" 의 zombie 부활.
 *    렌더링은 `Bookmark.isAlive()` 로 필터.
 */
public final class BookmarksConflictResolver {

    private BookmarksConflictResolver() {}

    public static List<Bookmark> merge(List<Bookmark> local, List<Bookmark> remote) {
        Map<String, Bookmark> byId = new LinkedHashMap<>();
        if (local != null) for (Bookmark b : local) keepWinner(byId, b);
        if (remote != null) for (Bookmark b : remote) keepWinner(byId, b);
        return new ArrayList<>(byId.values());
    }

    private static void keepWinner(Map<String, Bookmark> byId, Bookmark incoming) {
        if (incoming == null || incoming.id == null) return;
        Bookmark existing = byId.get(incoming.id);
        if (existing == null) {
            byId.put(incoming.id, incoming);
            return;
        }
        if (incoming.effectiveTimestamp() >= existing.effectiveTimestamp()) {
            byId.put(incoming.id, incoming);
        }
    }
}
