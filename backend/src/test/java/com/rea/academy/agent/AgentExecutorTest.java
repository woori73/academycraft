package com.rea.academy.agent;

import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.domain.admin.ReaAuditLogRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.AgentDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.security.AuthContext;
import com.rea.academy.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentExecutor: 파싱 → 정책 검사 → 도구 실행 → 감사 로그 파이프라인 테스트.
 * - 읽기 도구는 승인 없이 즉시 실행
 * - 쓰기 도구는 승인 없으면 "승인 필요" 거부, 실행되지 않음
 * - 학부모의 쓰기 도구 호출은 거부
 * - 모든 실행은 감사 로그에 기록
 */
@SpringBootTest
@Transactional
class AgentExecutorTest {

    private static final Long ACADEMY = 1L;

    @Autowired
    private AgentExecutor agentExecutor;
    @Autowired
    private MemberService memberService;
    @Autowired
    private ReaAuditLogRepository auditLogRepository;

    private AgentContext ownerCtx;
    private AgentContext parentCtx;

    @BeforeEach
    void setUp() {
        Long ownerId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                .name("원장").phone("010-0000-0001").build()).getId();
        ownerCtx = new AgentContext(AuthContext.PrincipalType.MEMBER, ACADEMY,
                ReaRole.ACADEMY_OWNER, ownerId, "원장", false);

        Long studentId = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name("학생A1").grade("고2").build()).getId();
        parentCtx = new AgentContext(AuthContext.PrincipalType.STUDENT, ACADEMY,
                ReaRole.PARENT, studentId, "학생A1", false);
    }

    private AgentContext approved(AgentContext base) {
        return new AgentContext(base.principalType(), base.academyId(), base.role(),
                base.principalId(), base.principalName(), true);
    }

    @Test
    @DisplayName("읽기 도구: 승인 없이 즉시 실행")
    void readTool_executesWithoutApproval() {
        AgentDto.ExecuteResponse resp = agentExecutor.execute(ownerCtx, "오늘 결석 학생");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getToolUsed()).isEqualTo("getAbsentStudents");
    }

    @Test
    @DisplayName("쓰기 도구: 승인 없으면 거부 + 실행 안 됨")
    void writeTool_withoutApproval_denied() {
        AgentDto.ExecuteResponse resp = agentExecutor.execute(ownerCtx, "학생 등록 김철수");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getToolUsed()).isEqualTo("registerStudent");
        assertThat(resp.getRequiresApproval()).isTrue();
        assertThat(memberService.getStudents(ACADEMY, "김철수")).isEmpty();
    }

    @Test
    @DisplayName("쓰기 도구: 승인 있으면 실행")
    void writeTool_withApproval_executes() {
        AgentDto.ExecuteResponse resp = agentExecutor.execute(approved(ownerCtx), "학생 등록 김철수");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getToolUsed()).isEqualTo("registerStudent");
        assertThat(memberService.getStudents(ACADEMY, "김철수")).hasSize(1);
    }

    @Test
    @DisplayName("학부모: 쓰기 도구 호출 거부 (승인 여부 무관)")
    void parent_writeTool_denied() {
        AgentDto.ExecuteResponse resp = agentExecutor.execute(approved(parentCtx), "학생 등록 김철수");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getRequiresApproval()).isFalse();
        assertThat(resp.getMessage()).contains("학부모");
        assertThat(memberService.getStudents(ACADEMY, "김철수")).isEmpty();
    }

    @Test
    @DisplayName("이해할 수 없는 명령: 실패 응답")
    void unknownCommand_fails() {
        AgentDto.ExecuteResponse resp = agentExecutor.execute(ownerCtx, "수학이 너무 어려워요");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getToolUsed()).isNull();
    }

    @Test
    @DisplayName("감사 로그: 실행·거부 모두 기록")
    void auditRecorded_forExecutionAndDenial() {
        int before = auditLogRepository
                .findByAcademyIdAndActionTypeOrderByIdDesc(ACADEMY, AuditActionType.AGENT_TOOL_EXECUTED)
                .size();

        agentExecutor.execute(ownerCtx, "오늘 결석 학생");           // 성공
        agentExecutor.execute(ownerCtx, "학생 등록 김철수");         // 승인 없음 거부

        var logs = auditLogRepository
                .findByAcademyIdAndActionTypeOrderByIdDesc(ACADEMY, AuditActionType.AGENT_TOOL_EXECUTED);
        assertThat(logs).hasSize(before + 2);
        assertThat(logs).allMatch(l -> "에이전트".equals(l.getTargetType()));
        assertThat(logs).anyMatch(l -> "getAbsentStudents".equals(l.getTargetName()));
        assertThat(logs).anyMatch(l -> "registerStudent".equals(l.getTargetName()));
    }
}
