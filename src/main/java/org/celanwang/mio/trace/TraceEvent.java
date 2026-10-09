package org.celanwang.mio.trace;

/**
 * 执行链路中的一条事件记录。
 *
 * @param timestamp 事件时间（毫秒）
 * @param type      事件类型：user / model / tool_call / tool_result / jev / confirm / ask
 * @param title     展示标题
 * @param detail    详细内容（纯文本或 JSON）
 */
public record TraceEvent(long timestamp, String type, String title, String detail) {
}
