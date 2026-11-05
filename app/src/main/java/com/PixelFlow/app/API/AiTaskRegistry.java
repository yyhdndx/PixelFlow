package com.PixelFlow.app.API;

import java.util.HashMap;
import java.util.Map;

/**
 * App 级 AI 任务注册表（P03 任务生命周期 / 页面外管理）。
 *
 * 耗时 AI 任务不依附具体 Activity：页面销毁后任务仍在注册表里继续运行，
 * 返回页面时按稳定 key 找回并恢复已有结果，不重复请求。
 *
 * 首版只保证 App 进程存活期间的页面切换与重建恢复；进程被杀后不假装任务仍在运行。
 */
public final class AiTaskRegistry {

    public enum State { WAITING, GENERATING, COMPLETED, CANCELLED, FAILED, INTERRUPTED }

    /** 单个 AI 任务：持有网络 Job、状态、结果与代数（用于丢弃迟到响应）。 */
    public static final class Task {
        public final String key;
        public volatile State state = State.WAITING;
        public volatile CommunityClient.Job job;
        public volatile int generation;
        /** 结果对象，由调用方按场景强转（AgentSearchResult / PostSummary / ...）。 */
        public volatile Object result;
        public volatile String error;

        public Task(String key) { this.key = key; }

        public boolean isRunning() {
            State s = state;
            return s == State.WAITING || s == State.GENERATING;
        }
    }

    private static final AiTaskRegistry INSTANCE = new AiTaskRegistry();

    public static AiTaskRegistry get() { return INSTANCE; }

    private final Map<String, Task> tasks = new HashMap<>();

    /** 按 key 取任务，没有则创建；页面恢复时据此找回进行中/已完成的任务。 */
    public synchronized Task getOrCreate(String key) {
        Task task = tasks.get(key);
        if (task == null) {
            task = new Task(key);
            tasks.put(key, task);
        }
        return task;
    }

    public synchronized Task peek(String key) { return tasks.get(key); }

    /** 明确停止：取消网络请求、递增代数让旧响应失效，并标记取消。 */
    public synchronized void cancel(String key) {
        Task task = tasks.get(key);
        if (task == null) return;
        task.generation++;
        if (task.job != null) task.job.cancel();
        if (task.isRunning()) task.state = State.CANCELLED;
    }

    public synchronized void remove(String key) { tasks.remove(key); }

    /** 退出 / 切换账号时清空全部 AI 任务，避免旧账号结果串入新账号。 */
    public synchronized void clearForAccount() {
        for (Task task : tasks.values()) {
            if (task.job != null) task.job.cancel();
        }
        tasks.clear();
    }
}
