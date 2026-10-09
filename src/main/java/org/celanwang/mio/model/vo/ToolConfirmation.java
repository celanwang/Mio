package org.celanwang.mio.model.vo;

import java.util.Map;

/** 页面展示的待确认工具调用。 */
public record ToolConfirmation(String toolCallId, String name, String description,
                               Map<String, Object> input) {
}
