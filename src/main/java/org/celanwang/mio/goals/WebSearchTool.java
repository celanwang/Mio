package org.celanwang.mio.goals;
import com.fasterxml.jackson.databind.*;
import io.agentscope.core.tool.*;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.*;
import reactor.core.publisher.Mono;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
public final class WebSearchTool extends ToolBase {
    private final String key;private final ObjectMapper json;private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public WebSearchTool(String key,ObjectMapper json){super(ToolBase.builder().name("search_web").description("搜索网页并返回标题、URL和摘要。未配置搜索服务时明确不可用；摘要是线索，不代表已阅读全文。禁止在搜索词中包含凭据。")
        .inputSchema(Map.of("type","object","properties",Map.of("query",Map.of("type","string","maxLength",500)),"required",List.of("query"),"additionalProperties",false)).readOnly(true).concurrencySafe(true));this.key=key;this.json=json;}
    @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState state){return Mono.just(key==null||key.isBlank()?PermissionDecision.deny("网页搜索尚未配置，可使用本地文档"):PermissionDecision.allow("公开网页查询"));}
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam call){return Mono.fromCallable(()->{
        if(key==null||key.isBlank())throw new IllegalStateException("网页搜索尚未配置");String query=(String)call.getInput().get("query");if(query==null||query.isBlank()||query.length()>500)throw new IllegalArgumentException("查询应为1–500字符");
        var request=HttpRequest.newBuilder(URI.create("https://api.search.brave.com/res/v1/web/search?count=5&q="+URLEncoder.encode(query,StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(20)).header("X-Subscription-Token",key).header("Accept","application/json").GET().build();
        var response=BoundedHttp.send(client,request,256000,Duration.ofSeconds(20));byte[] bytes=response.body();if(response.statusCode()!=200)throw new IllegalStateException("搜索服务暂不可用");
        var results=new ArrayList<Map<String,String>>();for(var item:json.readTree(bytes).path("web").path("results")){if(results.size()==5)break;String url=item.path("url").asText();URI uri;try{uri=URI.create(url);}catch(IllegalArgumentException invalid){continue;}if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)continue;String title=item.path("title").asText(),description=item.path("description").asText();results.add(Map.of("url",url,"title",title.substring(0,Math.min(300,title.length())),"description",description.substring(0,Math.min(2000,description.length()))));}
        return ToolResultBlock.text(json.writeValueAsString(Map.of("source","brave-web-search","observedAt",System.currentTimeMillis(),"results",results)));
    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());}
}
