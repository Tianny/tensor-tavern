package com.hify.common.log;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册请求日志拦截器（拦截所有路径）。
 */
@Configuration
public class LogWebConfig implements WebMvcConfigurer {

    private final RequestLogInterceptor requestLogInterceptor;

    public LogWebConfig(RequestLogInterceptor requestLogInterceptor) {
        this.requestLogInterceptor = requestLogInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLogInterceptor);
    }
}
