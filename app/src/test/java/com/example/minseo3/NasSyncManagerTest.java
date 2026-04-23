package com.example.minseo3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.minseo3.nas.FakeRemoteBookmarksRepository;
import com.example.minseo3.nas.FakeRemoteProgressRepository;
import com.example.minseo3.nas.RemoteProgressRepository;
import com.example.minseo3.nas.RemotePosition;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class NasSyncManagerTest {

    private InMemoryPrefs prefs;
    private FakeRemoteProgressRepository fake;
    private FakeRemoteBookmarksRepository fakeBm;
    private ExecutorService executor;
    private NasSyncManager nas;

    @Before
    public void setUp() {
        prefs = new InMemoryPrefs();
        fake = new FakeRemoteProgressRepository("device-test");
        fakeBm = new FakeRemoteBookmarksRepository();
        executor = new InlineExecutor();
        nas = new NasSyncManager(prefs, fake, fakeBm, executor);
    }

    @Test
    public void push_whenDisabled_isNoop() {
        prefs.enabled = false;
        nas.setConnectedForTest(true);

        nas.push("hash-1", "/tmp/a.txt", 100, 1000);

        assertEquals(0, fake.size());
    }

    @Test
    public void push_whenDisconnected_isNoop() {
        prefs.enabled = true;
        nas.setConnectedForTest(false);

        nas.push("hash-1", "/tmp/a.txt", 100, 1000);

        assertEquals(0, fake.size());
    }

    @Test
    public void push_whenEnabledAndConnected_delegatesToRepository() {
        prefs.enabled = true;
        nas.setConnectedForTest(true);

        nas.push("hash-1", "/tmp/a.txt", 100, 1000);

        assertEquals(1, fake.size());
        AtomicReference<RemotePosition> stored = new AtomicReference<>();
        fake.fetchOne("hash-1", new RemoteProgressRepository.Callback<RemotePosition>() {
            @Override public void onResult(RemotePosition v) { stored.set(v); }
            @Override public void onError(String message) {}
        });
        RemotePosition p = stored.get();
        assertNotNull(p);
        assertEquals(100, p.charOffset);
        assertEquals(1000, p.totalChars);
        assertEquals("device-test", p.deviceId);
        assertEquals("a.txt", p.fileName);
    }

    @Test
    public void fetchAll_whenDisabled_returnsEmptyMap() {
        prefs.enabled = false;
        nas.setConnectedForTest(true);

        AtomicReference<Map<String, RemotePosition>> result = new AtomicReference<>();
        nas.fetchAll(new RemoteProgressRepository.Callback<Map<String, RemotePosition>>() {
            @Override public void onResult(Map<String, RemotePosition> v) { result.set(v); }
            @Override public void onError(String message) {}
        });

        assertNotNull(result.get());
        assertTrue(result.get().isEmpty());
    }

    @Test
    public void fetchOne_whenDisconnected_returnsNull() {
        prefs.enabled = true;
        nas.setConnectedForTest(false);

        AtomicReference<RemotePosition> result = new AtomicReference<>();
        AtomicBoolean called = new AtomicBoolean(false);
        nas.fetchOne("hash-1", new RemoteProgressRepository.Callback<RemotePosition>() {
            @Override public void onResult(RemotePosition v) { result.set(v); called.set(true); }
            @Override public void onError(String message) {}
        });

        assertTrue(called.get());
        assertNull(result.get());
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    private static final class InMemoryPrefs implements NasSyncManager.Prefs {
        boolean enabled = false;
        String host = "", user = "", pass = "", path = "/소설/.minseo/", lanHost = "";
        int port = 5000, lanPort = 5000;

        @Override public boolean isEnabled()  { return enabled; }
        @Override public String  getHost()    { return host; }
        @Override public int     getPort()    { return port; }
        @Override public String  getUser()    { return user; }
        @Override public String  getPass()    { return pass; }
        @Override public String  getPath()    { return path; }
        @Override public String  getLanHost() { return lanHost; }
        @Override public int     getLanPort() { return lanPort; }
        @Override public void save(boolean enabled, String host, int port,
                                   String user, String pass, String path,
                                   String lanHost, int lanPort) {
            this.enabled = enabled; this.host = host; this.port = port;
            this.user = user; this.pass = pass; this.path = path;
            this.lanHost = lanHost; this.lanPort = lanPort;
        }
    }

    /** 호출 스레드에서 즉시 실행하는 ExecutorService. 테스트 결정론용. */
    private static final class InlineExecutor extends AbstractExecutorService {
        private volatile boolean shut = false;
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { shut = true; }
        @Override public java.util.List<Runnable> shutdownNow() { shut = true; return java.util.Collections.emptyList(); }
        @Override public boolean isShutdown() { return shut; }
        @Override public boolean isTerminated() { return shut; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
