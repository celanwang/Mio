package org.celanwang.mio.memory;

import org.celanwang.mio.trace.TraceEvent;

/**
 * 事件发布接缝：执行链路事件的统一发布入口。
 * 当前唯一实现是本地 JSONL 落盘（{@link EventLogStore}）；未来引入 MQ（如 RocketMQ）时
 * 新增实现按配置切换，业务代码不感知。
 * 实现必须 best-effort：发布失败只允许记日志，绝不阻断主流程。
 */
public interface EventPublisher {

    void publish(String sessionId, TraceEvent event);
}
