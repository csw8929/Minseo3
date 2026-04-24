package com.example.minseo3.nas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.minseo3.Bookmark;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 크로스 디바이스 북마크 V1 union 로직의 CRITICAL 시나리오 4개.
 * plan-eng-review (2026-04-23) 합의 스코프.
 */
public class BookmarksConflictResolverTest {

    /** 1. 같은 id 두 레코드 — 더 최신 createdAt 우선. 양쪽 alive 인 경우. */
    @Test
    public void merge_idCollision_latestCreatedAtWins() {
        Bookmark earlier = new Bookmark("id-1", "dev-A", 100, "earlier preview", 1000L, null);
        Bookmark later   = new Bookmark("id-1", "dev-B", 100, "later preview",   2000L, null);

        List<Bookmark> merged = BookmarksConflictResolver.merge(
                Collections.singletonList(earlier),
                Collections.singletonList(later));

        assertEquals(1, merged.size());
        assertEquals(2000L, merged.get(0).createdAt);
        assertEquals("dev-B", merged.get(0).deviceId);
        assertTrue(merged.get(0).isAlive());
    }

    /**
     * 2. 한쪽이 tombstone, 다른 쪽은 alive. tombstone 의 deletedAt 이 더 최신이면 tombstone 승리.
     * 좀비 레코드 부활 방지의 핵심 보장.
     */
    @Test
    public void merge_aliveVsTombstone_tombstoneWinsIfLater() {
        Bookmark aliveOnA   = new Bookmark("id-1", "dev-A", 100, "p", 1000L, null);
        Bookmark deletedOnB = new Bookmark("id-1", "dev-B", 100, "p", 1000L, 3000L); // deletedAt=3000

        // A pull 이 B 의 tombstone 을 로컬과 union 하는 시나리오
        List<Bookmark> merged = BookmarksConflictResolver.merge(
                Collections.singletonList(aliveOnA),
                Collections.singletonList(deletedOnB));

        assertEquals(1, merged.size());
        assertNotNull("tombstone 이 승리해야 — deletedAt 가 남아야 함", merged.get(0).deletedAt);
        assertEquals(3000L, merged.get(0).deletedAt.longValue());
    }

    /**
     * 3. 삭제 → 재생성 시나리오. 새 createdAt > 이전 deletedAt → 되살아남.
     * "삭제한 페이지에 다시 별 찍기" 가 정상 동작함을 보장.
     */
    @Test
    public void merge_recreateAfterDelete_revivesWithNewCreatedAt() {
        Bookmark oldDeleted = new Bookmark("id-1", "dev-A", 100, "p", 1000L, 2000L);
        Bookmark recreated  = new Bookmark("id-1", "dev-A", 100, "p", 3000L, null);

        List<Bookmark> merged = BookmarksConflictResolver.merge(
                Collections.singletonList(oldDeleted),
                Collections.singletonList(recreated));

        assertEquals(1, merged.size());
        assertTrue("재생성 레코드가 살아있어야 — deletedAt=null", merged.get(0).isAlive());
        assertEquals(3000L, merged.get(0).createdAt);
    }

    /**
     * 4. 이종 기기 페이지네이션 — 폰과 탭이 "같은 논리 페이지" 를 다른 charOffset 으로 찍음.
     * 서로 다른 deterministic id 가 생기므로 union 이 둘 다 살림 (중복으로 보이는 게 정상).
     */
    @Test
    public void merge_differentIdsSameLogicalPage_unionKeepsBoth() {
        Bookmark fromPhone  = new Bookmark("id-phone",  "dev-phone",  100, "p1", 1000L, null);
        Bookmark fromTablet = new Bookmark("id-tablet", "dev-tablet", 120, "p2", 1000L, null);

        List<Bookmark> merged = BookmarksConflictResolver.merge(
                Collections.singletonList(fromPhone),
                Collections.singletonList(fromTablet));

        assertEquals(2, merged.size());
        // 순서는 보장 안 함. 둘 다 살아있는지만 확인.
        for (Bookmark b : merged) assertTrue(b.isAlive());
    }

    /** 경계: 빈 리스트 + 비어있지 않은 리스트 merge — 빈 쪽은 영향 없어야. */
    @Test
    public void merge_emptyAndNonEmpty_returnsNonEmpty() {
        Bookmark only = new Bookmark("id-1", "dev-A", 100, "p", 1000L, null);

        List<Bookmark> merged = BookmarksConflictResolver.merge(
                Collections.emptyList(),
                Arrays.asList(only));

        assertEquals(1, merged.size());
        assertEquals("id-1", merged.get(0).id);
    }

    /** Null 안전성 — 한 쪽이 null 이어도 예외 없이 처리. */
    @Test
    public void merge_nullInput_treatedAsEmpty() {
        Bookmark local = new Bookmark("id-1", "dev-A", 100, "p", 1000L, null);

        List<Bookmark> merged = BookmarksConflictResolver.merge(
                Collections.singletonList(local), null);

        assertEquals(1, merged.size());
    }
}
