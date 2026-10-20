package com.rea.academy.agent.plan;

import org.springframework.http.HttpStatus;

/** 계획 API 오류. HTTP 상태와 기계가 읽는 코드를 함께 돌려준다. */
public class AgentPlanException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AgentPlanException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
