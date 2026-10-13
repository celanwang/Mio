package org.celanwang.mio.controller;

import org.celanwang.mio.config.WeiboProperties;
import org.celanwang.mio.service.WeiboMcpService;
import org.celanwang.mio.service.WeiboOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/weibo")
public class WeiboController {
    private static final String PENDING = WeiboController.class.getName() + ".authorization";
    private static final String FROM_CHAT = WeiboController.class.getName() + ".fromChat";
    private static final String PREVIEW_LOCALHOST = "http://localhost:63342";
    private static final String PREVIEW_LOOPBACK = "http://127.0.0.1:63342";
    private static final Set<String> PREVIEW_ORIGINS = Set.of(PREVIEW_LOCALHOST, PREVIEW_LOOPBACK);
    private final WeiboProperties properties;
    private final WeiboOAuthService oauth;
    private final WeiboMcpService mcp;

    public WeiboController(WeiboProperties properties, WeiboOAuthService oauth, WeiboMcpService mcp) {
        this.properties = properties;
        this.oauth = oauth;
        this.mcp = mcp;
    }

    @GetMapping("/status")
    @CrossOrigin(origins = {PREVIEW_LOCALHOST, PREVIEW_LOOPBACK})
    public ResponseEntity<WeiboMcpService.Status> status(HttpServletRequest request) {
        localOnly(request);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(mcp.status());
    }

    @GetMapping("/connect")
    public ResponseEntity<Void> connect(HttpServletRequest request, @RequestParam(required = false) String from) {
        localOnly(request);
        URI callback = properties.getCallbackUri();
        // localhost 和 127.0.0.1 的 Cookie 不共享，先在回调所属域创建浏览器会话。
        if (!callback.getHost().equalsIgnoreCase(request.getServerName()) || callback.getPort() != request.getServerPort()) {
            return redirect(callback.resolve("/api/weibo/connect" + ("chat".equals(from) ? "?from=chat" : "")));
        }
        var authorization = oauth.beginAuthorization();
        request.getSession(true).setAttribute(PENDING, authorization);
        request.getSession().setAttribute(FROM_CHAT, "chat".equals(from));
        return redirect(URI.create(authorization.url()));
    }

    @GetMapping("/callback")
    public ResponseEntity<?> callback(HttpServletRequest request,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String error) {
        localOnly(request);
        HttpSession session = request.getSession(false);
        var pending = session == null ? null : (WeiboOAuthService.Authorization) session.getAttribute(PENDING);
        boolean fromChat = session != null && Boolean.TRUE.equals(session.getAttribute(FROM_CHAT));
        if (session != null) {
            session.removeAttribute(PENDING); // 授权码及 state 只允许消费一次。
            session.removeAttribute(FROM_CHAT);
        }
        if (error != null) throw new IllegalStateException("微博授权未完成，请返回聊天页面重新连接。");
        oauth.finishAuthorization(pending, state, code);
        if (fromChat) return conversationAuthorizationComplete();
        try { mcp.connect(); } catch (IllegalStateException ignored) { /* 页面状态提供重试入口。 */ }
        return redirect(properties.getCallbackUri().resolve("/"));
    }

    private ResponseEntity<String> conversationAuthorizationComplete() {
        String nonce = UUID.randomUUID().toString();
        return ResponseEntity.ok().header("Content-Type", "text/html;charset=UTF-8")
                .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce
                        + "'; base-uri 'none'; frame-ancestors 'none'")
                .body("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>微博授权完成</title>"
                        + "<h1>微博授权已完成</h1><p>正在返回原对话，助手将继续刚才的请求。</p>"
                        + "<p>如果此页没有自动关闭，请关闭此页并返回原对话。</p>"
                        + "<script nonce=\"" + nonce + "\">window.close();</script></html>");
    }

    @PostMapping("/reconnect")
    @CrossOrigin(origins = {PREVIEW_LOCALHOST, PREVIEW_LOOPBACK})
    public WeiboMcpService.Status reconnect(HttpServletRequest request) {
        localOnly(request);
        String origin = request.getHeader("Origin");
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        String expected = request.getScheme() + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
        if (origin != null && !origin.equals(expected) && !PREVIEW_ORIGINS.contains(origin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        mcp.connect();
        return mcp.status();
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> authorizationError(IllegalStateException error) {
        return authorizationErrorPage(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> requestError(HttpServletRequest request, ResponseStatusException error) {
        String message = error.getReason() == null ? "微博连接请求被拒绝，请返回聊天页面检查服务地址。" : error.getReason();
        if (request.getRequestURI().endsWith("/status") || request.getRequestURI().endsWith("/reconnect")) {
            return ResponseEntity.status(error.getStatusCode()).header("Cache-Control", "no-store")
                    .body(Map.of("message", message));
        }
        return authorizationErrorPage(error.getStatusCode(), message);
    }

    private ResponseEntity<String> authorizationErrorPage(HttpStatusCode status, String reason) {
        String message = HtmlUtils.htmlEscape(reason);
        String home = HtmlUtils.htmlEscape(properties.getCallbackUri().resolve("/").toString());
        return ResponseEntity.status(status).header("Content-Type", "text/html;charset=UTF-8")
                .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'")
                .body("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>微博连接</title>"
                        + "<h1>微博暂未连接</h1><p>" + message + "</p><a href=\"" + home + "\">返回本机聊天页面</a></html>");
    }

    private static ResponseEntity<Void> redirect(URI uri) {
        return ResponseEntity.status(HttpStatus.FOUND).location(uri).header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer").build();
    }

    private static void localOnly(HttpServletRequest request) {
        if (!WeiboOAuthService.isLoopback(request.getRemoteAddr()) || !WeiboOAuthService.isLoopback(request.getServerName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "微博授权管理仅允许从本机访问。");
        }
    }
}
