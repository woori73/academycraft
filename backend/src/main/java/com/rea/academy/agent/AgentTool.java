package com.rea.academy.agent;

import java.util.Map;

/**
 * AI Agent가 호출할 수 있는 도구(Tool) 인터페이스 (2단계).
 *
 * 각 도구는 명확한 입·출력 스키마를 가지며, 기존 Service를 재사용한다.
 * - 읽기(Read) 도구: {@link #isWrite()} = false → 즉시 실행
 * - 쓰기(Write) 도구: {@link #isWrite()} = true → AgentContext.writeApproved=true일 때만 실행
 *
 * 화면-기능 분리: 순수 업무 로직. UI 의존성 없음.
 */
public interface AgentTool {

    /** 도구 고유 이름 (예: "registerStudent"). AgentDispatcher의 매핑 키. */
    String getName();

    /** 사람이 읽는 도구 설명 (한글). */
    String getDescription();

    /**
     * 입력 스키마: 파라미터명 → 설명(한글) 매핑.
     * 파라미터명에 "*" 접미가 있으면 선택값, 없으면 필수값.
     */
    Map<String, String> getInputSchema();

    /** 쓰기 도구 여부 (true이면 실행 전 쓰기 승인 검사가 필요). */
    boolean isWrite();

    /**
     * 도구 실행.
     *
     * @param input 입력 파라미터 (키=파라미터명, 값=파라미터값)
     * @param ctx   실행 컨텍스트 (학원 ID·역할·승인 여부)
     * @return 실행 결과 (JSON 직렬화 가능한 Map)
     * @throws IllegalArgumentException 입력 검증 실패 시
     */
    Map<String, Object> execute(Map<String, Object> input, AgentContext ctx);
}
