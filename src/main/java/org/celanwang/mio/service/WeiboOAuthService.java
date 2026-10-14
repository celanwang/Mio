package org.celanwang.mio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.config.WeiboProperties;
import org.celanwang.mio.security.Loopback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** OAuth 凭据只留在后端；授权采用 state、PKCE 和同一浏览器会话验证。 */
@Service
public class WeiboOAuthService {
    private final WeiboProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private JsonNode credentials;

    @Autowired
    public WeiboOAuthService(WeiboProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), Clock.systemUTC());
    }

    WeiboOAuthService(WeiboProperties properties, ObjectMapper json, HttpClient http, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.http = http;
        this.clock = clock;
        Path file = properties.getCredentialsFile();
        if (properties.isEnabled() && Files.exists(file)) {
            try {
                credentials = json.readTree(file.toFile());
            } catch (IOException error) {
                throw new IllegalStateException("无法读取本机微博授权文件，请检查文件权限或重新授权。");
            }
        }
    }

    public synchronized boolean isAuthorized() {
        return credentials != null && !credentials.path("access_token").asText().isBlank();
    }

    public Authorization beginAuthorization() {
        require(properties.isEnabled(), "微博 MCP 未启用。");
        URI resource = trusted(properties.getUrl());
        URI callback = properties.getCallbackUri();
        require(isLoopback(callback.getHost()) && "http".equals(callback.getScheme())
                && callback.getUserInfo() == null && callback.getQuery() == null && callback.getFragment() == null,
                "微博回调地址必须是本机 http://127.0.0.1 或 http://localhost 地址。");
        JsonNode protectedResource = get(resource.resolve("/.well-known/oauth-protected-resource"));
        String issuer = protectedResource.path("authorization_servers").path(0).asText();
        require(!issuer.isBlank(), "微博未返回 OAuth 授权服务地址。");
        URI issuerUri = trusted(URI.create(issuer));
        JsonNode metadata = get(issuerUri.resolve("/.well-known/oauth-authorization-server"));
        require(metadata.path("code_challenge_methods_supported").toString().contains("\"S256\""),
                "微博授权服务未提供 PKCE S256。");
        URI authorize = trusted(URI.create(required(metadata, "authorization_endpoint")));
        URI token = trusted(URI.create(required(metadata, "token_endpoint")));
        URI registration = trusted(URI.create(required(metadata, "registration_endpoint")));
        JsonNode client = postJson(registration, Map.of("client_name", "PersonalAgent",
                "redirect_uris", List.of(callback.toString()), "grant_types", List.of("authorization_code", "refresh_token"),
                "response_types", List.of("code"), "token_endpoint_auth_method", "none"));
        String clientId = required(client, "client_id");
        String state = nonce();
        String verifier = nonce();
        String challenge;
        try {
            challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException("无法创建微博 PKCE 授权请求。");
        }
        String url = UriComponentsBuilder.fromUri(authorize).queryParam("response_type", "code")
                .queryParam("client_id", clientId).queryParam("redirect_uri", callback)
                .queryParam("scope", "mcp").queryParam("resource", resource).queryParam("state", state)
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
        return new Authorization(url, state, verifier, clientId, token, callback, clock.instant().plusSeconds(600));
    }

    public synchronized void finishAuthorization(Authorization pending, String state, String code) {
        require(pending != null && state != null && code != null && !code.isBlank()
                        && clock.instant().isBefore(pending.expiresAt())
                        && MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8)),
                "微博授权已过期或不属于当前浏览器，请从聊天页面重新连接。");
        JsonNode result = postForm(trusted(pending.tokenEndpoint()), Map.of("grant_type", "authorization_code",
                "client_id", pending.clientId(), "code", code, "code_verifier", pending.verifier(),
                "redirect_uri", pending.callbackUri().toString(), "resource", properties.getUrl().toString()));
        save(result, pending.clientId(), pending.tokenEndpoint());
    }

    public synchronized String accessToken() {
        if (!properties.isEnabled() || !isAuthorized()) throw new AuthorizationRequiredException();
        if (clock.instant().getEpochSecond() + 60 >= credentials.path("expires_at").asLong()) {
            String refresh = credentials.path("refresh_token").asText();
            if (refresh.isBlank()) { credentials = null; throw new AuthorizationRequiredException(); }
            URI endpoint = trusted(URI.create(required(credentials, "token_endpoint")));
            try {
                JsonNode result = postForm(endpoint, Map.of("grant_type", "refresh_token", "refresh_token", refresh,
                        "client_id", required(credentials, "client_id"), "resource", properties.getUrl().toString()));
                save(result, required(credentials, "client_id"), endpoint);
            } catch (AuthorizationRequiredException failure) {
                credentials = null;
                throw failure;
            }
        }
        return required(credentials, "access_token");
    }

    private void save(JsonNode result, String clientId, URI tokenEndpoint) {
        String access = required(result, "access_token");
        String type = result.path("token_type").asText("Bearer");
        require("Bearer".equalsIgnoreCase(type), "微博返回了不支持的授权类型。");
        var saved = json.createObjectNode();
        saved.put("access_token", access);
        saved.put("refresh_token", result.path("refresh_token").asText(
                credentials == null || !clientId.equals(credentials.path("client_id").asText())
                        ? "" : credentials.path("refresh_token").asText()));
        saved.put("expires_at", clock.instant().getEpochSecond() + result.path("expires_in").asLong(3600));
        saved.put("client_id", clientId);
        saved.put("token_endpoint", tokenEndpoint.toString());
        Path file = properties.getCredentialsFile().toAbsolutePath().normalize();
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "auth-", ".tmp");
            restrictToOwner(temporary);
            json.writeValue(temporary.toFile(), saved);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            credentials = saved;
        } catch (IOException error) {
            throw new IllegalStateException("无法保存微博授权，请检查本机授权目录的写入权限。");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private void restrictToOwner(Path file) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(file))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        } else if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private JsonNode get(URI uri) {
        return send(HttpRequest.newBuilder(trusted(uri)).timeout(Duration.ofSeconds(20)).GET().build());
    }

    private JsonNode postJson(URI uri, Map<String, Object> body) {
        try {
            return send(HttpRequest.newBuilder(trusted(uri)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
        } catch (IOException error) {
            throw new IllegalStateException("无法创建微博授权请求。");
        }
    }

    private JsonNode postForm(URI uri, Map<String, String> body) {
        String form = new LinkedHashMap<>(body).entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(trusted(uri)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401) throw new AuthorizationRequiredException();
            if (response.statusCode() == 400) {
                try {
                    JsonNode failure = json.readTree(response.body());
                    if (failure != null && Set.of("invalid_grant", "invalid_token", "invalid_client")
                            .contains(failure.path("error").asText())) throw new AuthorizationRequiredException();
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* 使用下方 HTTP 错误提示。 */ }
            }
            require(response.statusCode() >= 200 && response.statusCode() < 300,
                    "微博授权服务返回 HTTP " + response.statusCode() + "，请检查账号、开发者认证和体验服务，必要时重新授权。");
            JsonNode result = json.readTree(response.body());
            require(result != null && !result.has("error"), "微博未完成授权，请重新连接。");
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("微博授权请求被中断，请重试。");
        } catch (IOException error) {
            throw new IllegalStateException("无法连接微博授权服务，请检查网络后重试。");
        }
    }

    private URI trusted(URI uri) {
        boolean official = "https".equals(uri.getScheme()) && Set.of("cli.weibo.com", "open.weibo.com").contains(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
        boolean localTest = "http".equals(properties.getUrl().getScheme()) && isLoopback(properties.getUrl().getHost())
                && "http".equals(uri.getScheme()) && properties.getUrl().getAuthority().equals(uri.getAuthority());
        require((official || localTest) && uri.getUserInfo() == null && uri.getFragment() == null,
                "微博授权端点必须使用官方 HTTPS 地址。");
        return uri;
    }

    private String nonce() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isLoopback(String host) {
        return Loopback.isLoopback(host);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String required(JsonNode node, String key) {
        String value = node.path(key).asText();
        require(!value.isBlank(), "微博授权响应缺少必要字段，请重新连接。");
        return value;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static class AuthorizationRequiredException extends IllegalStateException {
        public AuthorizationRequiredException() { super("微博授权尚未完成或已失效，请使用对话中的授权入口。"); }
    }

    // 禁止自动生成含 state / verifier 的 toString，避免调试时泄露授权信息。
    public record Authorization(String url, String state, String verifier, String clientId, URI tokenEndpoint,
                                URI callbackUri, Instant expiresAt) {
        @Override public String toString() { return "WeiboAuthorization[redacted]"; }
    }
}
