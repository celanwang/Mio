package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EvidenceKnowledgeTest {
    private Map<String,Object> hit(String id,int offset,String source){return Map.of("id",id,"revision",1L,"start_offset",offset,"end_offset",offset+100,"source",source);}
    @Test void fusionPromotesAgreementIncludesSemanticAndDiversifiesDocuments(){
        var lexical=new ArrayList<Map<String,Object>>();for(int i=0;i<20;i++)lexical.add(hit("long",i*100,"KEYWORD"));
        lexical.add(hit("both",0,"KEYWORD"));
        var hits=DocumentRanking.fuse(lexical,List.of(hit("semantic",0,"SEMANTIC"),hit("both",0,"SEMANTIC")));
        assertThat(hits.get(0)).containsEntry("id","both").containsEntry("source","KEYWORD+SEMANTIC");
        assertThat(hits.stream().filter(h->h.get("id").equals("long"))).hasSize(3);
        assertThat(hits).anyMatch(h->h.get("id").equals("semantic"));assertThat(hits.get(0)).containsKey("citation");
    }
    @Test void webReaderRequiresExplicitHttpsHostAndRemovesActiveContent() throws Exception {
        var tool=new WebReadTool(Set.of("example.com"),new ObjectMapper());
        assertThat(tool.validate("https://example.com/page").getHost()).isEqualTo("example.com");
        for(String url:List.of("http://example.com","https://example.com.evil.org","https://user@example.com","https://example.com:444","https://example.com/#x"))assertThatThrownBy(()->tool.validate(url)).isInstanceOf(IllegalArgumentException.class);
        assertThat(WebReadTool.publicAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(WebReadTool.publicAddress(InetAddress.getByName("fc00::1"))).isFalse();
        assertThat(WebReadTool.plainText("<html><head><style>hidden</style><script>alert(1)</script></head><body><h1>Title</h1><p>A &amp; B</p></body></html>")).contains("Title","A & B").doesNotContain("hidden","alert");
    }
    @Test void httpBodyIsBoundedBySizeAndCompleteResponseDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/big",exchange->{try(exchange){exchange.sendResponseHeaders(200,100);exchange.getResponseBody().write(new byte[100]);}});
        server.createContext("/slow",exchange->{try(exchange){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(1);exchange.getResponseBody().flush();try{Thread.sleep(400);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
        server.start();try{var client=HttpClient.newHttpClient();String root="http://127.0.0.1:"+server.getAddress().getPort();
            assertThatThrownBy(()->BoundedHttp.send(client,HttpRequest.newBuilder(URI.create(root+"/big")).build(),10,Duration.ofSeconds(2))).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->BoundedHttp.send(client,HttpRequest.newBuilder(URI.create(root+"/slow")).build(),100,Duration.ofMillis(100))).isInstanceOf(java.util.concurrent.TimeoutException.class);
        }finally{server.stop(0);}
    }
}
