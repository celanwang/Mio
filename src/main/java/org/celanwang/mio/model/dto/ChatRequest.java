package org.celanwang.mio.model.dto;


import lombok.Data;
import java.util.List;

@Data
public class ChatRequest {

    private String message;

    private String sessionId;

    // 前端只提交工具调用编号和决定，工具名及参数由后端的会话状态提供。
    private List<Confirmation> confirmations;

    public record Confirmation(String toolCallId, Boolean approved) {
    }

}
