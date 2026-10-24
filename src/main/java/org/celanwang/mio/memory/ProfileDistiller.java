package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Duration;

/**
 * 画像提炼器：用轻量模型把用户显式表达的偏好维护进用户画像（整页改写），
 * 并顺手产出实体关系三元组写入实体簿。
 * 网络结果由持久任务保存；写入在作业围栏事务内完成，错误由调度器记录并有限重试。
 */
@Component
public class ProfileDistiller {

    private static final String PROMPT = """
            你在维护一个个人助理的用户画像 Wiki，并从对话中提取实体关系。
            规则：
            - 只记录用户明确表达的偏好（口味/忌口、常用地址、预算、回复风格等），拿不准的不记录；
            - 健康、政治、宗教等敏感主题一律不记录；
            - 已有页面已覆盖的信息不要重复输出；
            - 仅更新 inferred 页面；explicit 人工页面不得覆盖，不得换 slug 重复创建其内容；
            - 更新已有推断页面时沿用其 slug，content 为整页重写后的完整内容（保留原有有效信息并融入新信息）；
            - 新页面 slug 用简短中文名；scope 默认 global，明确属于某业务领域时填领域名（如 mcd-ordering）；
            - triples 提取对话中出现的实体关系（如 用户-忌口-花生、用户-常用地址-某小区），没有则输出空数组；
            - 没有可记录内容时输出 {"pages":[],"triples":[]}；
            - 只输出严格 JSON，不要输出任何其他内容，格式：
              {"pages":[{"slug":"","title":"","scope":"global","content":"","confidence":0.9}],"triples":[{"subject":"","predicate":"","object":""}]}
            现有页面（slug | scope | source | title | content）：
            """;

    private final Model fastModel;
    private final ProfileStore profileStore;
    private final PgEntityStore entityStore;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public ProfileDistiller(@Qualifier("dashScopeFastModel") Model fastModel,
                            ProfileStore profileStore,
                            PgEntityStore entityStore,
                            ObjectMapper objectMapper,
                            @Value("${app.memory.distiller.enabled:true}") boolean enabled) {
        this.fastModel = fastModel;
        this.profileStore = profileStore;
        this.entityStore = entityStore;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public boolean enabled() {return enabled;}

    public record Plan(int schemaVersion,String messageId,long sourcePosition,long sourceTimestamp,
                       java.util.Map<String,Long> revisions,DistillResult result) {
        public Plan {
            if(schemaVersion!=1||messageId==null||messageId.isBlank()||sourcePosition<1||result==null||revisions==null||revisions.size()>20)throw new IllegalArgumentException("提炼检查点无效");
            revisions=java.util.Map.copyOf(revisions);
        }
    }
    /** Only a durable worker with a billing context may invoke the model. */
    public Plan prepare(EpisodeStore.Episode episode,org.celanwang.mio.memory.jobs.MemoryJob.Claim claim) throws Exception {
        if(!enabled)throw new IllegalStateException("画像提炼已停用");
        if(!episode.id().equals(claim.messageId())||!episode.role().equals("user"))throw new IllegalArgumentException("提炼来源不是当前用户消息");
        var snapshot=profileStore.snapshot();
        var revisions=new java.util.LinkedHashMap<String,Long>();StringBuilder pages=new StringBuilder();
        for(var page:snapshot.pages()) {
            if(revisions.size()>=20)break;
            String line=page.id()+" | "+page.scope()+" | "+page.source()+" | "+page.title()+" | "+page.content().trim()+"\n";
            // Never rewrite a page from a truncated body. Unseen existing ids fail the insert-only CAS below.
            if(pages.length()+line.length()>20000)continue;
            revisions.put(page.id(),snapshot.revisions().get(page.id()));pages.append(line);
        }
        String prompt=PROMPT+(pages.isEmpty()?"（空）":pages.toString())+"\n用户的话：\n"+episode.text();
        var call=org.celanwang.mio.memory.jobs.MemoryWorkContext.begin(org.celanwang.mio.memory.jobs.MemoryCallBudget.Kind.MODEL,prompt.length());
        var reported=new java.util.concurrent.atomic.AtomicLong(-1);boolean settled=false;
        try {
            var responses=fastModel.stream(List.of(new UserMessage(prompt)),null,GenerateOptions.builder().maxTokens(2048).build())
                    .doOnNext(chunk->{if(chunk.getUsage()!=null){var u=chunk.getUsage();if(u.getInputTokens()<0||u.getOutputTokens()<0)throw new IllegalStateException("无效模型用量");reported.accumulateAndGet((long)u.getInputTokens()+u.getOutputTokens(),Math::max);}})
                    .collectList().block(Duration.ofSeconds(30));
            boolean complete=responses!=null&&!responses.isEmpty()&&responses.get(responses.size()-1).getUsage()!=null;
            if(complete){var u=responses.get(responses.size()-1).getUsage();complete=(long)u.getInputTokens()+u.getOutputTokens()==reported.get();}
            call.settle(reported.get()<0?null:reported.get(),complete);settled=true;
            if(responses==null||responses.isEmpty())throw new IllegalStateException("提炼模型未返回内容");
            StringBuilder text=new StringBuilder();for(var block:responses.get(responses.size()-1).getContent())if(block instanceof TextBlock t)text.append(t.getText());
            int start=text.indexOf("{"),end=text.lastIndexOf("}");if(start<0||end<=start)throw new IllegalArgumentException("提炼结果不是 JSON");
            var strict=objectMapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var result=strict.readValue(text.substring(start,end+1),DistillResult.class);
            return new Plan(1,episode.id(),claim.sequence(),episode.timestamp(),revisions,result);
        } finally {if(!settled)call.settle(reported.get()<0?null:reported.get(),false);}
    }
    /** Called inside the job's fenced completion transaction. Every write must propagate failures. */
    public int apply(Plan plan,org.celanwang.mio.memory.jobs.MemoryJob.Claim claim) {
        if(!plan.messageId().equals(claim.messageId())||plan.sourcePosition()!=claim.sequence())throw new IllegalArgumentException("提炼检查点归属不匹配");
        long now=System.currentTimeMillis();int preserved=0;
        for(var op:plan.result().pages()) {
            if(op.title()==null||op.title().isBlank()||op.content()==null||op.content().isBlank())continue;
            String id=ProfileStore.sanitize(op.slug()==null||op.slug().isBlank()?op.title().trim():op.slug().trim());
            String scope=op.scope()==null||op.scope().isBlank()?ProfileStore.SCOPE_GLOBAL:op.scope().trim();
            if(!profileStore.saveInferred(new ProfilePage(id,op.title().trim(),scope,ProfileStore.SOURCE_INFERRED,
                    op.confidence()==null?.8:Math.max(0,Math.min(1,op.confidence())),now,op.content().trim()),plan.revisions().get(id),plan.sourcePosition(),plan.messageId()))preserved++;
        }
        var triples=plan.result().triples().stream().filter(t->t.subject()!=null&&!t.subject().isBlank()&&t.predicate()!=null&&!t.predicate().isBlank()&&t.object()!=null&&!t.object().isBlank())
                .map(t->new EntityStore.Triple(t.subject().trim(),t.predicate().trim(),t.object().trim(),plan.sourceTimestamp(),null,plan.sourceTimestamp())).toList();
        entityStore.appendFromMessage(plan.messageId(),triples);
        return preserved;
    }
    public record DistillResult(List<PageOp> pages,List<TripleOp> triples) {
        public DistillResult {pages=pages==null?List.of():List.copyOf(pages);triples=triples==null?List.of():List.copyOf(triples);
            if(pages.size()>20||triples.size()>100)throw new IllegalArgumentException("提炼条目过多");
            var ids=new java.util.HashSet<String>();for(var page:pages)if(page.title()!=null&&!page.title().isBlank()&&page.content()!=null&&!page.content().isBlank()){
                String id=ProfileStore.sanitize(page.slug()==null||page.slug().isBlank()?page.title().trim():page.slug().trim());if(!ids.add(id))throw new IllegalArgumentException("提炼页面身份重复");}
        }
    }
    public record PageOp(String slug,String title,String scope,String content,Double confidence) {
        public PageOp {bounded(slug,200);bounded(title,200);bounded(scope,100);bounded(content,6000);if(confidence!=null&&!Double.isFinite(confidence))throw new IllegalArgumentException("画像置信度无效");}
    }
    public record TripleOp(String subject,String predicate,String object) {
        public TripleOp {bounded(subject,200);bounded(predicate,100);bounded(object,1000);}
    }
    private static void bounded(String text,int limit){if(text!=null&&text.length()>limit)throw new IllegalArgumentException("提炼字段过长");}
}
