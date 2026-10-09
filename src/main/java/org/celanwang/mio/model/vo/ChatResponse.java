package org.celanwang.mio.model.vo;


import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class ChatResponse {

    private String sessionId;

    private String reply;

    private List<ToolConfirmation> pendingConfirmations;

}
