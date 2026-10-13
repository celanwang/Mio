package org.celanwang.mio.service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 微博对通知返回 202 + text/plain + 空 chunked body；SDK 0.17 要求显式空正文长度。 */
final class WeiboHttpClient extends HttpClient {
    private final HttpClient delegate;
    private WeiboHttpClient(HttpClient delegate) { this.delegate = delegate; }

    static Builder builder() { return new CompatibleBuilder(); }

    private static <T> HttpResponse.BodyHandler<T> normalize(HttpResponse.BodyHandler<T> handler) {
        return info -> {
            if (info.statusCode() != 202 || !info.headers().firstValue("Content-Type").orElse("").startsWith("text/plain")) {
                return handler.apply(info);
            }
            var headers = new LinkedHashMap<>(info.headers().map());
            headers.keySet().removeIf("content-length"::equalsIgnoreCase);
            headers.put("content-length", List.of("0"));
            HttpHeaders emptyBodyHeaders = HttpHeaders.of(headers, (name, value) -> true);
            return handler.apply(new HttpResponse.ResponseInfo() {
                public int statusCode() { return info.statusCode(); }
                public HttpHeaders headers() { return emptyBodyHeaders; }
                public Version version() { return info.version(); }
            });
        };
    }

    @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException { return delegate.send(request, normalize(handler)); }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return delegate.sendAsync(request, normalize(handler));
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                   HttpResponse.PushPromiseHandler<T> push) {
        return delegate.sendAsync(request, normalize(handler), push);
    }
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }

    private static class CompatibleBuilder implements Builder {
        private final Builder delegate = HttpClient.newBuilder().version(Version.HTTP_1_1);
        public Builder cookieHandler(CookieHandler value) { delegate.cookieHandler(value); return this; }
        public Builder connectTimeout(Duration value) { delegate.connectTimeout(value); return this; }
        public Builder sslContext(SSLContext value) { delegate.sslContext(value); return this; }
        public Builder sslParameters(SSLParameters value) { delegate.sslParameters(value); return this; }
        public Builder executor(Executor value) { delegate.executor(value); return this; }
        public Builder followRedirects(Redirect value) { delegate.followRedirects(value); return this; }
        public Builder version(Version value) { delegate.version(value); return this; }
        public Builder priority(int value) { delegate.priority(value); return this; }
        public Builder proxy(ProxySelector value) { delegate.proxy(value); return this; }
        public Builder authenticator(Authenticator value) { delegate.authenticator(value); return this; }
        public HttpClient build() { return new WeiboHttpClient(delegate.build()); }
    }
}
