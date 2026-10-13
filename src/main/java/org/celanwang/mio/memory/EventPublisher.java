package org.celanwang.mio.memory;

import org.celanwang.mio.trace.TraceEvent;

/**
 * 事件发布接缝：执行链路事件的统一发布入口。
 * 唯一实现为 PG 落表（{@link PgEventPublisher}）；未来引入 MQ（如 RocketMQ）时
 * 新增实现按配置切换，业务代码不感知。
 * 实现必须 best-effort：发布失败只允许记日志，绝不阻断主流程。
 */
public interface EventPublisher {

    void publish(String sessionId, TraceEvent event);
}
