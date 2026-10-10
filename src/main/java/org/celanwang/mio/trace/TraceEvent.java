package org.celanwang.mio.trace;

/**
 * 执行链路中的一条事件记录。
 *
 * @param timestamp 事件时间（毫秒）
 * @param node      链路图节点：user / qwen / jev / human / tool:&lt;工具名&gt; / memory:inject
 * @param type      事件类型：user / model / tool_call / tool_result / jev / confirm / ask / inject_decision
 * @param title     展示标题
 * @param detail    详细内容（纯文本或 JSON）
 */
public record TraceEvent(long timestamp, String node, String type, String title, String detail) {
}
