package com.rea.academy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 비동기 실행 설정.
 * 비밀번호 재설정 링크 발송 등 외부 연동을 HTTP 요청 스레드와 분리하여
 * 재시도·타임아웃이 API 응답 시간을 지연시키지 않도록 한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
