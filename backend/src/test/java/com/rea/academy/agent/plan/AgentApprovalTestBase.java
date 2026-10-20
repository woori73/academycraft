package com.rea.academy.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rea.academy.agent.AgentContext;
import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.domain.admin.ReaAcademy;
import com.rea.academy.domain.admin.ReaAcademyRepository;
import com.rea.academy.domain.admin.ReaAuditLogRepository;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.security.AuthContext;
import com.rea.academy.service.ClassService;
import com.rea.academy.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AI 쓰기 승인 API 테스트 공통 기반. 트랜잭션을 걸지 않는다(실제 커밋·동시성 검증을 위해).
 * 테스트마다 새 학원 ID 를 써서 서로 데이터가 섞이지 않게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AgentApprovalTestBase {

    private static final AtomicLong ACADEMY_SEQ = new AtomicLong(7000);

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected ReaMemberRepository memberRepository;
    @Autowired protected ReaStudentRepository studentRepository;
    @Autowired protected ReaAcademyRepository academyRepository;
    @Autowired protected ClassService classService;
    @Autowired protected ReaAgentPlanRepository planRepository;
    @Autowired protected ReaAuditLogRepository auditRepository;
    @Autowired protected AgentPlanService planService;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper om;

    protected long academyId;
    protected long ownerId;
    protected String ownerKey;
    protected String teacherKey;

    @BeforeEach
    void baseSetUp() {
        academyId = ACADEMY_SEQ.incrementAndGet();
        academyRepository.save(ReaAcademy.builder().id(academyId).name("승인테스트학원" + academyId).active(true).build());
        ownerId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(academyId).role(ReaRole.ACADEMY_OWNER).name("원장").phone("010-0000-0001").build()).getId();
        ownerKey = memberRepository.findById(ownerId).orElseThrow().getAuthKey();
        long teacherId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(academyId).role(ReaRole.TEACHER).name("강사").phone("010-0000-0002").build()).getId();
        teacherKey = memberRepository.findById(teacherId).orElseThrow().getAuthKey();
    }

    // ---------- HTTP 헬퍼 ----------

    protected MvcResult execute(String key, long academy, String body) throws Exception {
        return mvc.perform(post("/api/rea/" + academy + "/agent/execute")
                .header("X-Auth-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    protected MvcResult approve(String key, long academy, String planId, String hash) throws Exception {
        return mvc.perform(post("/api/rea/" + academy + "/agent/plans/" + planId + "/approve")
                .header("X-Auth-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planHash\":\"" + hash + "\"}")).andReturn();
    }

    protected MvcResult cancel(String key, long academy, String planId) throws Exception {
        return mvc.perform(post("/api/rea/" + academy + "/agent/plans/" + planId + "/cancel")
                .header("X-Auth-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
    }

    protected MvcResult getPlan(String key, long academy, String planId) throws Exception {
        return mvc.perform(get("/api/rea/" + academy + "/agent/plans/" + planId)
                .header("X-Auth-Key", key)).andReturn();
    }

    protected JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() ? om.createObjectNode() : om.readTree(s);
    }

    /** 원장으로 학생 등록 계획을 만들고 plan 노드를 돌려준다 */
    protected JsonNode registerPlan(String name) throws Exception {
        MvcResult r = execute(ownerKey, academyId,
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"" + name + "\",\"parentPhone\":\"010-1234-5678\"}}");
        JsonNode plan = body(r).get("plan");
        if (plan == null || plan.isNull()) {
            throw new AssertionError("계획이 생성되지 않았습니다: " + r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        return plan;
    }

    protected long createClassId(long academy, String name) {
        // 담당 강사는 같은 학원 회원만 지정할 수 있으므로 다른 학원에는 그 학원 강사를 만들어 지정한다
        Long teacher = academy == academyId ? ownerId
                : memberService.createMember(MemberDto.CreateRequest.builder().academyId(academy)
                        .role(ReaRole.TEACHER).name("타학원강사").phone("010-0000-0003").build()).getId();
        return classService.createClass(academy, ClassDto.CreateRequest.builder().name(name).teacherId(teacher).build()).getId();
    }

    protected long createStudentId(long academy, String name) {
        return memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(academy).name(name).grade("중1").build()).getId();
    }

    protected int studentCount(String name) {
        return memberService.getStudents(academyId, name).size();
    }

    protected String statusOf(String planId) {
        return planRepository.findById(planId).orElseThrow().getStatus().name();
    }

    protected long auditCount(AuditActionType type) {
        return auditRepository.findByAcademyIdAndActionTypeOrderByIdDesc(academyId, type).size();
    }

    /** DB 에 저장된 회원 정보는 그대로 두고, 서비스에 직접 넘길 원장 컨텍스트를 만든다(HTTP 인증 계층 우회 시나리오용) */
    protected AgentContext ownerContext() {
        return new AgentContext(AuthContext.PrincipalType.MEMBER, academyId, ReaRole.ACADEMY_OWNER,
                ownerId, "원장", false);
    }
}
