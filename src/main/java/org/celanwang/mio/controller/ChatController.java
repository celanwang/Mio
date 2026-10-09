package org.celanwang.mio.controller;


import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.model.vo.ChatResponse;
import org.celanwang.mio.service.ChatService;
import io.agentscope.core.model.ModelException;

import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLHandshakeException;

@RestController
public class ChatController {

    @Resource
    private ChatService chatService;


    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    // 允许 IDEA 的本地预览页面访问聊天接口。
    @CrossOrigin(origins = {"http://localhost:63342", "http://127.0.0.1:63342"})
    public Mono<ChatResponse> chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }


    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleRequestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of("message", error.getReason() == null ? "请求无法处理。" : error.getReason()));
    }

    @ExceptionHandler(ModelException.class)
    public ResponseEntity<Map<String, String>> handleModelError(ModelException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLHandshakeException) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message",
                        "连接模型服务时 HTTPS 握手失败，请检查网络或代理，稍后重试。"));
            }
            if (cause instanceof ConnectException || cause instanceof HttpTimeoutException) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message",
                        "暂时无法连接模型服务，请检查网络、代理和模型地址，稍后重试。"));
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message",
                "模型服务请求失败，请查看后端日志，检查模型配置与服务状态。"));
    }
}
