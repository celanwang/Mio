package org.celanwang.mio.orchestration;

import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ToolConfirmation;
import org.celanwang.mio.model.vo.WeiboAction;
import reactor.core.publisher.Mono;
import java.util.List;

public interface TaskWorker {
    record Input(boolean first,String userText,List<ChatRequest.Confirmation> decisions,List<TaskFacts.BoundInput> bindings) {
        public Input(boolean first,String userText,List<ChatRequest.Confirmation> decisions){this(first,userText,decisions,List.of());}
        public Input {decisions=List.copyOf(decisions);bindings=List.copyOf(bindings);}
    }
    record Result(String reply, boolean needsInput, List<ToolConfirmation> pending, WeiboAction action, boolean failed) {
        public Result(String reply,boolean needsInput,List<ToolConfirmation> pending,WeiboAction action){this(reply,needsInput,pending,action,false);}
    }
    Mono<Result> run(TaskRecord task, GoalRecord goal, Input input, TaskExecution execution);
}
