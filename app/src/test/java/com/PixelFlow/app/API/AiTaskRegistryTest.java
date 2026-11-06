package com.PixelFlow.app.API;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class AiTaskRegistryTest {
    @Test public void firstStartRunsOnceAndRecreatedSubscriberReceivesResult() {
        AiTaskRegistry registry = new AiTaskRegistry();
        AiTaskRegistry.Task task = registry.getOrCreate("question");
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<CommunityClient.Callback<Object>> network = new AtomicReference<>();
        List<Object> oldPage = new ArrayList<>(), newPage = new ArrayList<>();
        AiTaskRegistry.Subscription subscription = registry.subscribe(task, Runnable::run, value -> oldPage.add(value.result));
        assertTrue(registry.start(task, callback -> { starts.incrementAndGet(); network.set(callback); return null; }));
        assertFalse(registry.start(task, callback -> { starts.incrementAndGet(); return null; }));
        subscription.close();
        int oldCount = oldPage.size();
        registry.subscribe(task, Runnable::run, value -> newPage.add(value.result));
        network.get().onSuccess("answer");
        assertEquals(1, starts.get());
        assertEquals(oldCount, oldPage.size());
        assertEquals("answer", newPage.get(newPage.size() - 1));
        assertEquals(AiTaskRegistry.State.COMPLETED, task.state);
    }

    @Test public void cancelledAndPreviousAccountCallbacksCannotRestoreResults() {
        AiTaskRegistry registry = new AiTaskRegistry();
        AtomicReference<CommunityClient.Callback<Object>> callback = new AtomicReference<>();
        AiTaskRegistry.Task task = registry.getOrCreate("q");
        registry.start(task, value -> { callback.set(value); return null; });
        registry.cancel("q");
        callback.get().onSuccess("late");
        assertNull(task.result);
        assertEquals(AiTaskRegistry.State.CANCELLED, task.state);
        registry.clearForAccount();
        AiTaskRegistry.Task replacement = registry.getOrCreate("q");
        callback.get().onSuccess("previous account");
        assertNull(replacement.result);
        assertEquals(AiTaskRegistry.State.NEW, replacement.state);
    }

    @Test public void queuedNotificationsAreDiscardedAfterAccountClear() {
        AiTaskRegistry registry = new AiTaskRegistry();
        List<Runnable> delivery = new ArrayList<>();
        AtomicInteger events = new AtomicInteger();
        registry.subscribe(registry.getOrCreate("q"), delivery::add, value -> events.incrementAndGet());
        registry.clearForAccount();
        for (Runnable runnable : delivery) runnable.run();
        assertEquals(0, events.get());
    }

    @Test public void completedResultExpiresWithoutCancellingRunningTask() {
        AtomicLong clock = new AtomicLong();
        AiTaskRegistry registry = new AiTaskRegistry(clock::get);
        AiTaskRegistry.Task complete = registry.getOrCreate("complete");
        registry.start(complete, callback -> { callback.onSuccess("answer"); return null; });
        AiTaskRegistry.Task running = registry.getOrCreate("running");
        registry.start(running, callback -> null);
        clock.set(15 * 60 * 1000L);
        assertNull(registry.peek("complete"));
        assertSame(running, registry.peek("running"));
        assertEquals(AiTaskRegistry.State.EXPIRED, complete.state);
    }

    @Test public void expiryIsDeliveredAndPartialCancellationCannotBecomeSuccess() {
        AtomicLong clock = new AtomicLong();
        AiTaskRegistry registry = new AiTaskRegistry(clock::get);
        AiTaskRegistry.Task task = registry.getOrCreate("stream");
        AtomicReference<CommunityClient.Callback<Object>> network = new AtomicReference<>();
        registry.start(task, callback -> { network.set(callback); return null; });
        com.google.gson.JsonObject partial = new com.google.gson.JsonObject();
        partial.addProperty("answer", "partial");
        registry.publish(task, task.generation, partial);
        registry.cancel(task.key);
        assertSame(partial, task.result);
        network.get().onSuccess("late complete");
        assertEquals(AiTaskRegistry.State.CANCELLED, task.state);
        List<Runnable> deliveries = new ArrayList<>();
        List<AiTaskRegistry.State> states = new ArrayList<>();
        registry.subscribe(task, deliveries::add, value -> states.add(value.state));
        clock.set(15 * 60 * 1000L);
        assertNull(registry.peek(task.key));
        for (Runnable delivery : deliveries) delivery.run();
        assertTrue(states.contains(AiTaskRegistry.State.EXPIRED));
        assertNull(task.result);
    }

    @Test public void runningTasksHaveBoundedCapacity() {
        AiTaskRegistry registry = new AiTaskRegistry();
        for (int i = 0; i < 32; i++) registry.start(registry.getOrCreate("q" + i), callback -> null);
        try { registry.getOrCreate("overflow"); fail("capacity must reject"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("已满")); }
        assertTrue(registry.peek("q0").isRunning());
    }
    @Test public void latestTaskIsScopedToAccountSceneAndTarget() {
        AiTaskRegistry registry = new AiTaskRegistry();
        CommunityClient api = new CommunityClient("http://localhost/", Runnable::run);
        try {
            api.restore("token", "refresh", "alice");
            AiTaskRegistry.Task first = registry.getOrCreate(AiTaskRegistry.identity(api, "rules", "1", "old"));
            AiTaskRegistry.Task latest = registry.getOrCreate(AiTaskRegistry.identity(api, "rules", "1", "new"));
            registry.getOrCreate(AiTaskRegistry.identity(api, "rules", "2", "other"));
            assertSame(latest, registry.latest(api, "rules", "1"));
            assertSame(first, registry.getOrCreate(first.key));
            assertSame(first, registry.latest(api, "rules", "1"));
            assertNull(registry.latest(api, "summary", "1"));
            api.restore("other", "other-refresh", "bob");
            assertNull(registry.latest(api, "rules", "1"));
        } finally { api.close(); }
    }
}
