package org.celanwang.mio.config;

import org.celanwang.mio.security.LocalOnlyInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 接口最小鉴权：对话接口与全部管理接口统一回收至本机回环检查。 */
@Configuration
public class WebSecurityConfig implements WebMvcConfigurer {

    private final boolean localOnly;

    public WebSecurityConfig(@Value("${app.security.local-only:true}") boolean localOnly) {
        this.localOnly = localOnly;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (localOnly) {
            registry.addInterceptor(new LocalOnlyInterceptor())
                    .addPathPatterns("/chat", "/api/**");
        }
    }
}
