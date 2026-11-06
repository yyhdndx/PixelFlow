package com.PixelFlow.app.API;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

public final class AiTaskRegistry {
    public enum State { NEW, WAITING, PREPARING, GENERATING, COMPLETED, CANCELLED, FAILED, INTERRUPTED, EXPIRED }
    public interface Observer { void onChanged(Task task); }
    public interface Subscription { void close(); }
    public interface Starter { CommunityClient.Job start(CommunityClient.Callback<Object> callback); }

    public static final class Task {
        public final String key;
        public volatile State state = State.NEW;
        public volatile CommunityClient.Job job;
        public volatile int generation;
        public volatile Object result;
        public volatile String error;
        private final long epoch;
        private long finishedAt;
        private int bytes;
        private final List<Binding> observers = new ArrayList<>();

        private Task(String key, long epoch) { this.key = key; this.epoch = epoch; }
        public boolean isRunning() {
            return state == State.WAITING || state == State.PREPARING || state == State.GENERATING;
        }
    }
    private static final class Binding {
        final Executor delivery;
        final Observer observer;
        volatile boolean active = true;
        Binding(Executor delivery, Observer observer) { this.delivery = delivery; this.observer = observer; }
    }

    private static final AiTaskRegistry INSTANCE = new AiTaskRegistry();
    public static AiTaskRegistry get() { return INSTANCE; }
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final Gson json = new Gson();
    private long epoch;
    private static final long TTL = 15 * 60 * 1000L;
    private static final int MAX_TASKS = 32, MAX_RESULT = 256 * 1024, MAX_BYTES = 2 * 1024 * 1024;

    public AiTaskRegistry() { this(System::currentTimeMillis); }
    AiTaskRegistry(LongSupplier clock) { this.clock = clock; }

    public static String identity(CommunityClient api, String scene, Object... inputs) {
        return new Gson().toJson(new Object[] {api.baseUrl(), api.username(), api.sessionVersion(), scene, inputs});
    }

    public synchronized Task getOrCreate(String key) {
        expire();
        if (key == null || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024)
            throw new IllegalStateException("AI 输入过长，请缩短内容后重试");
        Task task = tasks.get(key);
        if (task != null) return task;
        while (tasks.size() >= MAX_TASKS && evict(null)) { }
        if (tasks.size() >= MAX_TASKS) throw new IllegalStateException("AI 任务已满，请稍后重试");
        task = new Task(key, epoch);
        tasks.put(key, task);
        return task;
    }

    public synchronized Task peek(String key) { expire(); return tasks.get(key); }
    public synchronized boolean isCurrent(Task task) { return task != null && current(task); }

    public Subscription subscribe(Task task, Executor delivery, Observer observer) {
        Binding binding = new Binding(delivery, observer);
        synchronized (this) {
            if (!current(task)) return () -> { };
            task.observers.add(binding);
            dispatch(task, binding);
        }
        return () -> {
            synchronized (AiTaskRegistry.this) {
                binding.active = false;
                task.observers.remove(binding);
            }
        };
    }

    public boolean start(Task task, Starter starter) {
        final int version;
        synchronized (this) {
            if (!current(task) || task.state != State.NEW) return false;
            version = ++task.generation;
            task.state = State.WAITING;
            notifyChanged(task);
        }
        try {
            CommunityClient.Job job = starter.start(new CommunityClient.Callback<Object>() {
                public void onSuccess(Object value) { finish(task, version, value, null); }
                public void onFailure(String message) { finish(task, version, null, message); }
                public void onStarted() {
                    synchronized (AiTaskRegistry.this) {
                        if (current(task) && task.generation == version && task.isRunning()) {
                            task.state = State.PREPARING;
                            notifyChanged(task);
                        }
                    }
                }
            });
            synchronized (this) {
                if (!current(task) || version != task.generation || !task.isRunning()) {
                    if (job != null) job.cancel();
                } else task.job = job;
            }
        } catch (RuntimeException error) {
            finish(task, version, null, "AI 请求未启动，请重试");
        }
        return true;
    }

    public synchronized void publish(Task task, int version, Object partial) {
        if (!current(task) || task.generation != version || !task.isRunning()) return;
        int bytes = json.toJson(partial).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_RESULT || retainedBytes() - task.bytes + bytes > MAX_BYTES) {
            task.error = "AI 结果空间已满，请缩短输入后重试";
            invalidate(task, State.INTERRUPTED);
            return;
        }
        task.result = partial;
        task.bytes = bytes;
        task.state = State.GENERATING;
        notifyChanged(task);
    }

    public synchronized void generating(Task task) {
        if (current(task) && task.isRunning()) {
            task.state = State.GENERATING;
            notifyChanged(task);
        }
    }

    private synchronized void finish(Task task, int version, Object value, String error) {
        if (!current(task) || task.generation != version || !task.isRunning()) return;
        boolean partial = error != null && task.result instanceof com.google.gson.JsonObject;
        if (partial) value = task.result;
        int bytes = value == null ? 0 : json.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_RESULT) { value = null; bytes = 0; error = "AI 结果过大，请缩短输入后重试"; }
        while (retainedBytes() - task.bytes + bytes > MAX_BYTES && evict(task)) { }
        if (retainedBytes() - task.bytes + bytes > MAX_BYTES) { value = null; bytes = 0; error = "AI 结果空间已满，请重试"; }
        task.result = value;
        task.bytes = bytes;
        task.error = error;
        task.state = error == null ? State.COMPLETED : partial ? State.INTERRUPTED : State.FAILED;
        task.finishedAt = clock.getAsLong();
        task.job = null;
        notifyChanged(task);
    }

    public synchronized void cancel(String key) {
        Task task = tasks.get(key);
        if (task == null) return;
        invalidate(task, State.CANCELLED);
    }
    public synchronized void remove(String key) {
        Task task = tasks.get(key);
        if (task != null) { invalidate(task, State.CANCELLED); tasks.remove(key); }
    }
    public synchronized void clearForAccount() {
        ++epoch;
        for (Task task : tasks.values()) {
            invalidate(task, State.CANCELLED);
            for (Binding binding : task.observers) binding.active = false;
            task.observers.clear();
        }
        tasks.clear();
    }
    private boolean current(Task task) { return task.epoch == epoch && tasks.get(task.key) == task; }
    private void invalidate(Task task, State state) {
        ++task.generation;
        if (task.job != null) task.job.cancel();
        task.job = null;
        boolean keepPartial = state == State.CANCELLED && task.epoch == epoch
                && task.result instanceof com.google.gson.JsonObject;
        if (!keepPartial) {
            task.result = null;
            task.bytes = 0;
        }
        task.state = state;
        task.finishedAt = clock.getAsLong();
        notifyChanged(task);
    }
    private void expire() {
        long now = clock.getAsLong();
        for (Task task : new ArrayList<>(tasks.values())) {
            if (!task.isRunning() && task.state != State.NEW && now - task.finishedAt >= TTL) {
                invalidate(task, State.EXPIRED);
                tasks.remove(task.key);
            }
        }
    }
    private int retainedBytes() { int size = 0; for (Task task : tasks.values()) size += task.bytes; return size; }
    private boolean evict(Task excluded) {
        for (Task task : new ArrayList<>(tasks.values())) {
            if (task != excluded && !task.isRunning() && task.state != State.NEW && task.observers.isEmpty()) {
                invalidate(task, State.EXPIRED);
                tasks.remove(task.key);
                return true;
            }
        }
        return false;
    }
    private void notifyChanged(Task task) {
        for (Binding binding : new ArrayList<>(task.observers)) dispatch(task, binding);
    }
    private void dispatch(Task task, Binding binding) {
        long expected = epoch;
        binding.delivery.execute(() -> {
            synchronized (AiTaskRegistry.this) {
                if (!binding.active || expected != epoch || (!current(task) && task.state != State.EXPIRED)) return;
            }
            binding.observer.onChanged(task);
        });
    }
}
