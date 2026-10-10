package org.celanwang.mio.memory;

/**
 * 记忆后台任务的异步执行边界。
 * 当前唯一实现是本地单线程池（{@link LocalMemoryTaskExecutor}）；未来记忆流水线 MQ 化时
 * 新增实现把任务作为事件投递，业务代码不感知。
 * 实现必须保证：任务异常不外抛到调用方（fail-open），调用方永远非阻塞。
 */
public interface MemoryTaskExecutor {

    void execute(Runnable task);
}
