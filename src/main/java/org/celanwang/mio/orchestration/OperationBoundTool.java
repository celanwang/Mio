package org.celanwang.mio.orchestration;

import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.ToolResultBlock;
import reactor.core.publisher.Mono;

/** Trusted operation identity is supplied out of band, never by model tool parameters. */
public interface OperationBoundTool {
    String operationScope();
    Mono<ToolResultBlock> callWithOperation(ToolCallParam call,String logicalId,String scopeKey);
}
