package com.rea.academy.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REA 보안 인터셉터 등록.
 */
@Configuration
public class ReaWebConfig implements WebMvcConfigurer {

    private final ReaSecurityInterceptor reaSecurityInterceptor;

    public ReaWebConfig(ReaSecurityInterceptor reaSecurityInterceptor) {
        this.reaSecurityInterceptor = reaSecurityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(reaSecurityInterceptor)
                .addPathPatterns("/api/rea/**");
    }
}
