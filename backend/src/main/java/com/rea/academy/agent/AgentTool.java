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

    /**
     * 쓰기 도구의 변경 미리보기 (P1-a). 읽기 전용이며 데이터를 바꾸지 않는다.
     *
     * <ul>
     *   <li>입력을 검증하고, 참조하는 학생·반이 {@code ctx.academyId()} 소속인지 서버가 직접 조회해 확인한다.
     *   <li>반환값은 승인 화면에 그대로 표시되므로 모델이 만든 문장이 아니라 <b>서버가 입력에서 생성</b>한 값만 담는다.
     *   <li>시각 등 호출마다 달라지는 값은 넣지 않는다 (승인 시 재생성한 미리보기와 비교해 대상 변경을 탐지한다).
     * </ul>
     *
     * 쓰기 도구는 반드시 구현해야 한다. 구현하지 않으면 계획을 만들 수 없다(fail-closed).
     *
     * @throws IllegalArgumentException 입력·대상 검증 실패 시
     */
    default Map<String, Object> preview(Map<String, Object> input, AgentContext ctx) {
        throw new UnsupportedOperationException("쓰기 도구는 preview 를 구현해야 합니다: " + getName());
    }
}
