package com.rea.academy.agent;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent API 보안 HTTP 테스트 — H2 인메모리 + MockMvc.
 * 검증: 401(인증키 누락·무효) / 403(테넌트 격리·역할 위반) / 역할별 정상 실행.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentControllerSecurityTest {

    private static final Long ACADEMY_A = 1L;
    private static final Long ACADEMY_B = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ReaMemberRepository memberRepository;

    @Autowired
    private ReaStudentRepository studentRepository;

    private String ownerKeyA;
    private String teacherKeyA;
    private String parentKeyA;

    @BeforeEach
    void setUp() {
        long ownerIdA = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.ACADEMY_OWNER)
                .name("원장A").phone("010-0000-0001").build()).getId();
        ownerKeyA = memberRepository.findById(ownerIdA).orElseThrow().getAuthKey();

        long teacherIdA = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.TEACHER)
                .name("강사A").phone("010-0000-0002").build()).getId();
        teacherKeyA = memberRepository.findById(teacherIdA).orElseThrow().getAuthKey();

        long studentA1 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("학생A1").grade("고2").build()).getId();
        parentKeyA = studentRepository.findById(studentA1).orElseThrow().getAuthKey();
    }

    private MvcResult execute(String key, Long academyId, String command, boolean writeApproved)
            throws Exception {
        String body = "{\"command\":\"" + command + "\",\"writeApproved\":" + writeApproved + "}";
        var builder = post("/api/rea/" + academyId + "/agent/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            builder.header("X-Auth-Key", key);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private String contentOf(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("인증키 없이 호출 → 401")
    void noKey_unauthorized() throws Exception {
        MvcResult result = execute(null, ACADEMY_A, "오늘 결석 학생", false);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("무효 인증키 → 401")
    void invalidKey_unauthorized() throws Exception {
        MvcResult result = execute("invalid-key", ACADEMY_A, "오늘 결석 학생", false);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("다른 학원 경로 → 403")
    void otherAcademy_forbidden() throws Exception {
        MvcResult result = execute(ownerKeyA, ACADEMY_B, "오늘 결석 학생", false);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("학부모 호출 → 403 (POST는 쓰기이므로)")
    void parent_forbidden() throws Exception {
        MvcResult result = execute(parentKeyA, ACADEMY_A, "오늘 결석 학생", false);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("원장 읽기 명령: 200 + 성공")
    void owner_readCommand_ok() throws Exception {
        mockMvc.perform(post("/api/rea/" + ACADEMY_A + "/agent/execute")
                        .header("X-Auth-Key", ownerKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"오늘 결석 학생\",\"writeApproved\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.toolUsed").value("getAbsentStudents"));
    }

    @Test
    @DisplayName("강사 읽기 명령: 200 + 성공")
    void teacher_readCommand_ok() throws Exception {
        mockMvc.perform(post("/api/rea/" + ACADEMY_A + "/agent/execute")
                        .header("X-Auth-Key", teacherKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"오늘 결석 학생\",\"writeApproved\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.toolUsed").value("getAbsentStudents"));
    }

    @Test
    @DisplayName("원장 쓰기 명령(승인 없음): 200 + 승인 필요 응답, 실행 안 됨")
    void owner_writeWithoutApproval_requiresApproval() throws Exception {
        MvcResult result = execute(ownerKeyA, ACADEMY_A, "학생 등록 보안학생", false);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String content = contentOf(result);
        assertThat(content).contains("\"requiresApproval\":true");
        assertThat(content).contains("승인");
    }

    // given 원장 / when writeApproved=true 로 쓰기를 요청하면 / then 400 으로 거부되고 아무것도 저장되지 않는다 (불리언 우회 차단)
    @Test
    @DisplayName("원장 writeApproved=true 쓰기 요청: 400 거부 + 데이터 무변경 (불리언 승인 우회 차단)")
    void owner_writeApprovedBoolean_rejected() throws Exception {
        mockMvc.perform(post("/api/rea/" + ACADEMY_A + "/agent/execute")
                        .header("X-Auth-Key", ownerKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"학생 등록 보안학생\",\"writeApproved\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRITE_APPROVED_NOT_ACCEPTED"));

        assertThat(memberService.getStudents(ACADEMY_A, "보안학생")).isEmpty();
    }

    // given 강사 / when writeApproved=true 로 쓰기를 요청하면 / then 400 거부, 승인 없이 쓰기 요청 시에는 초안만 보이고 저장되지 않는다
    @Test
    @DisplayName("강사 쓰기 요청: writeApproved=true 는 400, 그 외에는 서버 초안만(저장 없음)")
    void teacher_write_isDraftOnly_andBooleanRejected() throws Exception {
        mockMvc.perform(post("/api/rea/" + ACADEMY_A + "/agent/execute")
                        .header("X-Auth-Key", teacherKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"학생 등록 강사등록학생\",\"writeApproved\":true}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/rea/" + ACADEMY_A + "/agent/execute")
                        .header("X-Auth-Key", teacherKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"학생 등록 강사등록학생\",\"writeApproved\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("OWNER_ONLY"))
                .andExpect(jsonPath("$.draftPreview.changes").exists())
                .andExpect(jsonPath("$.plan").doesNotExist());

        assertThat(memberService.getStudents(ACADEMY_A, "강사등록학생")).isEmpty();
    }

    @Test
    @DisplayName("도구 목록 조회: 200 + 8개 도구")
    void listTools_ok() throws Exception {
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/agent/tools")
                        .header("X-Auth-Key", ownerKeyA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    @DisplayName("이해할 수 없는 명령: 200 + 실패 응답")
    void unknownCommand_fails() throws Exception {
        mockMvc.perform(post("/api/rea/" + ACADEMY_A + "/agent/execute")
                        .header("X-Auth-Key", ownerKeyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"수학이 너무 어려워요\",\"writeApproved\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }
}
