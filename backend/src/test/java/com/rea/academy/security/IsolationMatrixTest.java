package com.rea.academy.security;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.service.AttendanceService;
import com.rea.academy.service.ClassService;
import com.rea.academy.service.ConsultationService;
import com.rea.academy.service.LearningService;
import com.rea.academy.service.MemberService;
import com.rea.academy.service.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REA 6단계 작업 2 (2-1) — 학원 간 정보 격리 전수 검증 (HTTP 레벨).
 *
 * <p>학원A의 원장/강사/학부모가 학원B의 리소스에 접근하면 전부 403이어야 한다.
 * 위조 academyId 경로(999)·인증키 누락(401)·인증키 갱신 후 구 키(401)·
 * 타 학원 학생 이름 검색(빈 결과, 403 아님)도 함께 검증한다.
 * H2 인메모리 + MockMvc (Spring Security 필터 체인 포함), 가명 더미 데이터.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class IsolationMatrixTest {

    private static final Long ACADEMY_A = 1L;
    private static final Long ACADEMY_B = 2L;
    private static final Long FORGED_ACADEMY = 999L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MemberService memberService;
    @Autowired
    private ClassService classService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private NoticeService noticeService;
    @Autowired
    private ConsultationService consultationService;
    @Autowired
    private LearningService learningService;
    @Autowired
    private ReaMemberRepository memberRepository;
    @Autowired
    private ReaStudentRepository studentRepository;

    private long ownerIdA;
    private String ownerKeyA;
    private String teacherKeyA;
    private String parentKeyA;
    private long studentA1;
    private long ownerIdB;
    private long studentB;
    private long classB;
    private long noticeB;
    private long consultationB;
    private long homeworkB;

    @BeforeEach
    void setUp() {
        // ===== 학원A =====
        ownerIdA = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.ACADEMY_OWNER)
                .name("원장A").phone("010-0000-0001").build()).getId();
        ownerKeyA = memberRepository.findById(ownerIdA).orElseThrow().getAuthKey();

        long teacherIdA = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.TEACHER)
                .name("강사A").phone("010-0000-0002").build()).getId();
        teacherKeyA = memberRepository.findById(teacherIdA).orElseThrow().getAuthKey();

        studentA1 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("학생A1").grade("고2")
                .parentName("학부모A1").parentPhone("010-0000-0011").build()).getId();
        parentKeyA = studentRepository.findById(studentA1).orElseThrow().getAuthKey();

        // ===== 학원B (격리 검증 대상 데이터) =====
        ownerIdB = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_B).role(ReaRole.ACADEMY_OWNER)
                .name("원장B").phone("010-0000-0003").build()).getId();

        studentB = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_B).name("학생B-고유명").grade("중3")
                .parentName("학부모B").parentPhone("010-0000-0021").build()).getId();

        classB = classService.createClass(ACADEMY_B, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY_B).name("수학반B").teacherId(ownerIdB).build()).getId();

        attendanceService.check(ACADEMY_B, AttendanceDto.CheckRequest.builder()
                .studentId(studentB).eventType("CHECK_IN").build());

        noticeB = noticeService.create(ACADEMY_B, NoticeDto.CreateRequest.builder()
                .title("B학원 공지").content("B학원 전용 공지").build())
                .getNotice().getId();

        consultationB = consultationService.create(ACADEMY_B,
                ConsultationDto.CreateRequest.builder()
                        .studentId(studentB).type("INQUIRY")
                        .title("B상담").content("B 상담 내용").build())
                .getConsultation().getId();

        homeworkB = learningService.createHomework(ACADEMY_B,
                LearningDto.HomeworkCreateRequest.builder()
                        .classId(classB).title("B숙제").content("B 숙제 내용")
                        .dueDate("2026-10-20").build()).getHomework().getId();

        learningService.createGrade(ACADEMY_B, LearningDto.GradeCreateRequest.builder()
                .studentId(studentB).examName("B중간고사").subject("수학")
                .score(90).fullScore(100).examDate("2026-10-01").build());
    }

    // ===== 헬퍼 =====

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder req, String authKey) {
        if (authKey != null) {
            req.header(AuthKeyFilter.AUTH_KEY_HEADER, authKey);
        }
        return req;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String bodyUtf8(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String extractJsonString(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalStateException(field + "를 찾을 수 없음: " + body);
    }

    /** 학원B 리소스에 대한 읽기 경로 전체 (매트릭스 행) */
    private List<String> crossAcademyReadPaths() {
        List<String> paths = new ArrayList<>();
        paths.add("/api/rea/2/students");
        paths.add("/api/rea/2/students/" + studentB);
        paths.add("/api/rea/2/classes");
        paths.add("/api/rea/2/classes/" + classB);
        paths.add("/api/rea/2/attendance/roster?classId=" + classB + "&date=2026-10-13");
        paths.add("/api/rea/2/attendance/student/" + studentB);
        paths.add("/api/rea/2/attendance/monthly?yearMonth=2026-10");
        paths.add("/api/rea/2/notices");
        paths.add("/api/rea/2/notices/" + noticeB);
        paths.add("/api/rea/2/consultations");
        paths.add("/api/rea/2/consultations/" + consultationB);
        paths.add("/api/rea/2/homework");
        paths.add("/api/rea/2/grades/student/" + studentB);
        paths.add("/api/rea/2/reports/student/" + studentB);
        paths.add("/api/rea/2/progress/student/" + studentB);
        paths.add("/api/rea/2/attendance/settings");
        paths.add("/api/rea/2/audit-logs");
        paths.add("/api/rea/2/dashboard/stats");
        return paths;
    }

    // ===== 2-1a: 원장 — 타 학원 읽기/쓰기 전부 403 =====

    @Test
    @DisplayName("원장(A): 학원B의 학생·반·출결·공지·상담·숙제·성적·리포트 읽기 → 전부 403")
    void ownerCrossAcademy_reads_all403() throws Exception {
        for (String path : crossAcademyReadPaths()) {
            mockMvc.perform(withKey(get(path), ownerKeyA))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("원장(A): 학원B에 반·출결·숙제·성적·공지 쓰기 → 전부 403")
    void ownerCrossAcademy_writes_all403() throws Exception {
        mockMvc.perform(withKey(json(post("/api/rea/2/classes"),
                        "{\"name\":\"위조반\",\"teacherId\":" + ownerIdB + "}"), ownerKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(json(post("/api/rea/2/attendance/check"),
                        "{\"studentId\":" + studentB + ",\"eventType\":\"CHECK_IN\"}"), ownerKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(json(post("/api/rea/2/homework"),
                        "{\"classId\":" + classB + ",\"title\":\"위조\",\"content\":\"위조\","
                                + "\"dueDate\":\"2026-10-20\"}"), ownerKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(json(post("/api/rea/2/grades"),
                        "{\"studentId\":" + studentB + ",\"examName\":\"위조\","
                                + "\"subject\":\"수학\",\"score\":80,\"fullScore\":100}"), ownerKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(json(post("/api/rea/2/notices"),
                        "{\"title\":\"위조\",\"content\":\"위조\"}"), ownerKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(json(post("/api/rea/2/consultations"),
                        "{\"studentId\":" + studentB + ",\"type\":\"INQUIRY\","
                                + "\"title\":\"위조\"}"), ownerKeyA))
                .andExpect(status().isForbidden());
    }

    // ===== 2-1b: 강사 — 타 학원 접근 403 =====

    @Test
    @DisplayName("강사(A): 학원B의 학생·반·출결·성적·리포트·숙제 조회 → 전부 403")
    void teacherCrossAcademy_all403() throws Exception {
        for (String path : List.of(
                "/api/rea/2/students",
                "/api/rea/2/students/" + studentB,
                "/api/rea/2/classes",
                "/api/rea/2/classes/" + classB,
                "/api/rea/2/attendance/student/" + studentB,
                "/api/rea/2/grades/student/" + studentB,
                "/api/rea/2/reports/student/" + studentB,
                "/api/rea/2/homework",
                "/api/rea/2/consultations/" + consultationB)) {
            mockMvc.perform(withKey(get(path), teacherKeyA))
                    .andExpect(status().isForbidden());
        }
    }

    // ===== 2-1c: 학부모 — 타 학원 접근 403 =====

    @Test
    @DisplayName("학부모(A): 학원B의 공지·학생·상담 조회 → 전부 403")
    void parentCrossAcademy_all403() throws Exception {
        for (String path : List.of(
                "/api/rea/2/notices",
                "/api/rea/2/notices/" + noticeB,
                "/api/rea/2/students/" + studentB,
                "/api/rea/2/consultations",
                "/api/rea/2/grades/student/" + studentB)) {
            mockMvc.perform(withKey(get(path), parentKeyA))
                    .andExpect(status().isForbidden());
        }
    }

    // ===== 2-1d: 위조 academyId · 인증키 누락 =====

    @Test
    @DisplayName("위조 academyId 경로(/api/rea/999/students)를 A학원 키로 접근 → 403")
    void forgedAcademyId_returns403() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/students", FORGED_ACADEMY), ownerKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(get("/api/rea/{a}/students", FORGED_ACADEMY), teacherKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("인증키 없이 주요 API 호출 → 전부 401")
    void noAuthKey_majorEndpoints_returns401() throws Exception {
        for (String path : List.of(
                "/api/rea/1/students",
                "/api/rea/1/classes",
                "/api/rea/1/attendance/student/" + studentA1,
                "/api/rea/1/notices",
                "/api/rea/1/consultations",
                "/api/rea/1/homework",
                "/api/rea/1/grades/student/" + studentA1,
                "/api/rea/1/reports/student/" + studentA1)) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ===== 2-1e: 검색 — 타 학원 학생 이름 검색은 빈 결과(403 아님) =====

    @Test
    @DisplayName("강사(A)가 B학원 학생 이름으로 검색 → 200 + 0건 (빈 결과, 403 아님)")
    void searchOtherAcademyStudentName_returnsEmptyNot403() throws Exception {
        MvcResult result = mockMvc.perform(withKey(
                        get("/api/rea/1/search").param("query", "학생B-고유명"), teacherKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(result)).contains("\"students\":[]");
    }

    @Test
    @DisplayName("검색 대조군: 본인 학원 학생 이름 검색 → 200 + 결과 포함")
    void searchOwnAcademyStudentName_returnsHit() throws Exception {
        MvcResult result = mockMvc.perform(withKey(
                        get("/api/rea/1/search").param("query", "학생A1"), teacherKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(result)).contains("학생A1");
    }

    // ===== 2-4: 세션 만료 — 인증키 갱신 후 구 키는 401 =====

    @Test
    @DisplayName("인증키 갱신 후 구 키로 호출 → 401, 신 키 → 200 (세션 무효화)")
    void keyRenewal_oldKeyReturns401() throws Exception {
        MvcResult renewResult = mockMvc.perform(withKey(
                        post("/api/rea/1/members/{id}/renew-key", ownerIdA), ownerKeyA))
                .andExpect(status().isOk())
                .andReturn();
        String newKey = extractJsonString(bodyUtf8(renewResult), "authKey");
        assertThat(newKey).isNotEqualTo(ownerKeyA);

        // 구 키 → 401 (세션 만료)
        mockMvc.perform(withKey(get("/api/rea/1/students"), ownerKeyA))
                .andExpect(status().isUnauthorized());
        // 신 키 → 200
        mockMvc.perform(withKey(get("/api/rea/1/students"), newKey))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("학생(학부모) 인증키 갱신 후 구 키로 호출 → 401")
    void studentKeyRenewal_oldKeyReturns401() throws Exception {
        MvcResult renewResult = mockMvc.perform(withKey(
                        post("/api/rea/1/students/{id}/renew-key", studentA1), ownerKeyA))
                .andExpect(status().isOk())
                .andReturn();
        String newParentKey = extractJsonString(bodyUtf8(renewResult), "authKey");
        assertThat(newParentKey).isNotEqualTo(parentKeyA);

        mockMvc.perform(withKey(get("/api/rea/1/students/{id}", studentA1), parentKeyA))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(withKey(get("/api/rea/1/students/{id}", studentA1), newParentKey))
                .andExpect(status().isOk());
    }

    // ===== 회귀: 격리 검증이 기존 정상 흐름을 깨지 않았는지 =====

    @Test
    @DisplayName("대조군: 원장(A)이 본인 학원 리소스 조회 → 200 (격리 규칙이 정상 접근을 막지 않음)")
    void ownerOwnAcademy_still200() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/1/students"), ownerKeyA))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(get("/api/rea/1/reports/student/" + studentA1), ownerKeyA))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(get("/api/rea/1/grades/student/" + studentA1), parentKeyA))
                .andExpect(status().isOk());
    }
}
