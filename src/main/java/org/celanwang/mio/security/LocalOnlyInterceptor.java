package org.celanwang.mio.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 本机回环拦截器：对话接口与管理接口仅允许从本机访问，与本机部署形态匹配。
 * 不引入账号与会话体系；部署形态变化时再升级。
 */
public class LocalOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!Loopback.isLoopback(request.getRemoteAddr()) || !Loopback.isLoopback(request.getServerName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅允许从本机访问。");
        }
        return true;
    }
}
