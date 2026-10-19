package org.celanwang.mio.model.vo;

import java.util.Map;

/** 页面展示的待确认工具调用；firstSeen 标记首次出现的新工具（无任何人工确认记录），提示核对描述与参数。 */
public record ToolConfirmation(String toolCallId, String name, String description,
                               Map<String, Object> input, boolean firstSeen, String contractHash, String taskId, String agentName) {
    public ToolConfirmation(String toolCallId,String name,String description,Map<String,Object> input,boolean firstSeen) {
        this(toolCallId,name,description,input,firstSeen,null,null,null);
    }
    public ToolConfirmation(String toolCallId,String name,String description,Map<String,Object> input,boolean firstSeen,String contractHash) {
        this(toolCallId,name,description,input,firstSeen,contractHash,null,null);
    }
}
