package org.celanwang.mio.memory;

/**
 * 记忆后台任务的异步执行边界。
 * 仅用于可重建的画像缓存与习惯扫描；原文派生任务使用独立持久作业模块。
 * 任务异常和队列拒绝不影响主对话；容量必须有界，不能在调用线程执行任务。
 */
public interface MemoryTaskExecutor {

    void execute(Runnable task);
}
