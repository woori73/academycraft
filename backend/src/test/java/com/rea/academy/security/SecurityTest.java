package com.rea.academy.security;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.service.MemberService;
import com.rea.academy.service.NoticeService;
import com.rea.academy.service.ConsultationService;
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

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안(1단계) HTTP 테스트 — H2 인메모리 + MockMvc (Spring Security 필터 체인 포함).
 *
 * 검증: 401(인증키 누락·무효) / 403(테넌트 격리·역할 위반) / 역할별 정상 접근.
 * MockMvc는 테스트 스레드에서 동기 디스패치되므로 @Transactional 사용 가능.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityTest {

    private static final Long ACADEMY_A = 1L;
    private static final Long ACADEMY_B = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberService memberService;

    @Autowired
    private NoticeService noticeService;

    @Autowired
    private ConsultationService consultationService;

    @Autowired
    private ReaMemberRepository memberRepository;

    @Autowired
    private ReaStudentRepository studentRepository;

    private String ownerKeyA;
    private String teacherKeyA;
    private String parentKeyA;
    private String parentKeyA2;
    private long studentA1;
    private long studentA2;
    private long noticeA1;
    private long consultationA1;
    private long consultationA2;

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

        studentA1 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("학생A1").grade("고2")
                .parentName("학부모A1").parentPhone("010-0000-0011").build()).getId();
        parentKeyA = studentRepository.findById(studentA1).orElseThrow().getAuthKey();

        studentA2 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("학생A2").grade("고1").build()).getId();
        parentKeyA2 = studentRepository.findById(studentA2).orElseThrow().getAuthKey();

        // B학원 원장 (테넌트 격리 테스트용)
        memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_B).role(ReaRole.ACADEMY_OWNER)
                .name("원장B").phone("010-0000-0003").build());

        // PARENT 권한 테스트용 픽스처 (Service 직접 생성 — 인증 컨텍스트 불필요)
        noticeA1 = noticeService.create(ACADEMY_A, NoticeDto.CreateRequest.builder()
                .title("학부모 공지").content("읽음 확인 테스트용 공지").build())
                .getNotice().getId();
        consultationA1 = consultationService.create(ACADEMY_A,
                ConsultationDto.CreateRequest.builder()
                        .studentId(studentA1).type("INQUIRY")
                        .title("문의1").content("내용1").build())
                .getConsultation().getId();
        consultationA2 = consultationService.create(ACADEMY_A,
                ConsultationDto.CreateRequest.builder()
                        .studentId(studentA2).type("INQUIRY")
                        .title("문의2").build())
                .getConsultation().getId();
    }

    // ===== 요청 빌더 =====

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder req, String authKey) {
        if (authKey != null) {
            req.header(AuthKeyFilter.AUTH_KEY_HEADER, authKey);
        }
        return req;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Mock 응답 본문을 UTF-8로 디코딩 (기본 Latin-1 디코딩 시 한글 깨짐 방지) */
    private static String bodyUtf8(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ===== 401: 인증키 누락·무효 =====

    @Test
    @DisplayName("인증키 없이 API 호출 → 401")
    void noAuthKey_returns401() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/students", ACADEMY_A), null))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("위조된 인증키로 호출 → 401")
    void forgedAuthKey_returns401() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/students", ACADEMY_A), "FORGED-KEY-000"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("빈 인증키 헤더로 호출 → 401")
    void blankAuthKey_returns401() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/students", ACADEMY_A), ""))
                .andExpect(status().isUnauthorized());
    }

    // ===== 403: 테넌트 격리 =====

    @Test
    @DisplayName("A학원 인증키로 B학원 URL 접근 → 403")
    void crossAcademyAccess_returns403() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{b}/students", ACADEMY_B), ownerKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A학원 인증키로 B학원에 쓰기 시도 → 403")
    void crossAcademyWrite_returns403() throws Exception {
        mockMvc.perform(withKey(json(post("/api/rea/{b}/notices", ACADEMY_B), "{}"), ownerKeyA))
                .andExpect(status().isForbidden());
    }

    // ===== 403: 학부모 권한 =====

    @Test
    @DisplayName("학부모가 다른 학생 데이터 조회 → 403")
    void parentOtherStudent_returns403() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/students/{s}", ACADEMY_A, studentA2), parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모가 쓰기 API 호출 → 403")
    void parentWrite_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notices", ACADEMY_A),
                                "{\"title\":\"무단 공지\",\"content\":\"차단되어야 함\"}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모가 학원 회원 목록 조회 → 403")
    void parentMemberList_returns403() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/members", ACADEMY_A), parentKeyA))
                .andExpect(status().isForbidden());
    }

    // ===== 403: 강사 원장 전용 API =====

    @Test
    @DisplayName("강사가 강사 등록(원장 전용) 호출 → 403")
    void teacherRegisterMember_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/members", ACADEMY_A),
                                "{\"role\":\"TEACHER\",\"name\":\"무단강사\",\"phone\":\"010-9999-9999\"}"),
                        teacherKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("강사가 학원 설정 변경(원장 전용) 호출 → 403")
    void teacherAcademySettings_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(put("/api/rea/{a}/info", ACADEMY_A), "{\"name\":\"무단 변경\"}"),
                        teacherKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("강사가 학생 삭제(원장 전용) 호출 → 403")
    void teacherDeleteStudent_returns403() throws Exception {
        mockMvc.perform(withKey(
                        delete("/api/rea/{a}/students/{s}", ACADEMY_A, studentA2), teacherKeyA))
                .andExpect(status().isForbidden());
    }

    // ===== 정상 케이스 =====

    @Test
    @DisplayName("원장: 학원 전체 읽기·쓰기 → 200")
    void ownerFullAccess_returns200() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/students", ACADEMY_A), ownerKeyA))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/members", ACADEMY_A),
                                "{\"role\":\"TEACHER\",\"name\":\"정상강사\",\"phone\":\"010-1111-2222\"}"),
                        ownerKeyA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("강사: 읽기 전체 + 학생 등록(허용된 쓰기) → 200")
    void teacherAllowedWork_returns200() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/students", ACADEMY_A), teacherKeyA))
                .andExpect(status().isOk());
        MvcResult createdResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/students", ACADEMY_A),
                                "{\"name\":\"정상학생\",\"grade\":\"중3\"}"),
                        teacherKeyA))
                .andExpect(status().isOk())
                .andReturn();
        // Mock 응답 기본 인코딩이 Latin-1이므로 UTF-8로 명시 디코딩
        assertThat(bodyUtf8(createdResult)).contains("정상학생");
    }

    @Test
    @DisplayName("강사: 학원 정보 조회(원장 영역 읽기) → 403 아님")
    void teacherReadAcademyInfo_not403() throws Exception {
        // 학원 미등록 상태라 업무상 400 — 권한(403)이 통과했음을 의미
        mockMvc.perform(withKey(get("/api/rea/{a}/info", ACADEMY_A), teacherKeyA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("학부모: 본인 자녀 조회 → 200")
    void parentOwnChild_returns200() throws Exception {
        MvcResult childResult = mockMvc.perform(
                        withKey(get("/api/rea/{a}/students/{s}", ACADEMY_A, studentA1), parentKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(childResult)).contains("학생A1");
    }

    @Test
    @DisplayName("학부모: 학원 공지 목록 조회 → 200")
    void parentNoticeList_returns200() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/notices", ACADEMY_A), parentKeyA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증키 로그인(key-login)은 헤더 없이 공개 접근 → 200")
    void keyLogin_isPublic() throws Exception {
        mockMvc.perform(json(post("/api/rea/auth/key-login"),
                        "{\"authKey\":\"" + ownerKeyA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ACADEMY_OWNER")));
    }

    @Test
    @DisplayName("학생 인증키로 key-login → PARENT 역할로 로그인")
    void studentKeyLogin_returnsParentRole() throws Exception {
        mockMvc.perform(json(post("/api/rea/auth/key-login"),
                        "{\"authKey\":\"" + parentKeyA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PARENT")));
    }

    // ===== PARENT 허용 목록 보완 (phase5-task1b) — 허용된 API는 200 =====

    @Test
    @DisplayName("학부모: 본인 알림 목록 조회 → 200 (파라미터 없이 X-Auth-Key만으로)")
    void parentNotificationList_returns200() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{a}/notifications", ACADEMY_A), parentKeyA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("학부모: 미읽음 건수·알림 설정 조회 → 200")
    void parentNotificationMeta_returns200() throws Exception {
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/notifications/unread-count", ACADEMY_A), parentKeyA))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/notifications/settings", ACADEMY_A), parentKeyA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("학부모: 본인 상담 스레드 목록·상세 조회 → 200")
    void parentConsultationReads_returns200() throws Exception {
        MvcResult listResult = mockMvc.perform(
                        withKey(get("/api/rea/{a}/consultations", ACADEMY_A), parentKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(listResult)).contains("문의1");
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/consultations/{id}", ACADEMY_A, consultationA1), parentKeyA))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("학부모: 문의 등록 (POST /consultations) → 200, 본인 명의로 기록")
    void parentCreateInquiry_returns200() throws Exception {
        MvcResult result = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations", ACADEMY_A),
                                "{\"studentId\":" + studentA1 + ",\"type\":\"INQUIRY\","
                                        + "\"title\":\"등원 시간 문의\",\"content\":\"몇 시까지 등원인가요?\"}"),
                        parentKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(result)).contains("\"studentId\":" + studentA1);
    }

    @Test
    @DisplayName("학부모: 타 학생 ID로 문의 등록 시도 → 본인 명의로 강제 (타 학생 스레드 생성 안 됨)")
    void parentCreateInquiry_otherStudent_forcedToOwn() throws Exception {
        MvcResult result = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations", ACADEMY_A),
                                "{\"studentId\":" + studentA2 + ",\"type\":\"INQUIRY\","
                                        + "\"title\":\"위조 시도\"}"),
                        parentKeyA))
                .andExpect(status().isOk())
                .andReturn();
        String body = bodyUtf8(result);
        assertThat(body).contains("\"studentId\":" + studentA1);
        assertThat(body).doesNotContain("\"studentId\":" + studentA2);
    }

    @Test
    @DisplayName("학부모: 공지 상세 — X-Auth-Key만으로 자동 학부모 뷰 (myRead 포함) → 200 (NTC-04)")
    void parentNoticeDetail_autoParentView() throws Exception {
        MvcResult result = mockMvc.perform(withKey(
                        get("/api/rea/{a}/notices/{id}", ACADEMY_A, noticeA1), parentKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(result)).contains("myRead");
    }

    @Test
    @DisplayName("학부모: 공지 읽음 확인 (POST /notices/{id}/read) → 200")
    void parentMarkNoticeRead_returns200() throws Exception {
        MvcResult result = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notices/{id}/read", ACADEMY_A, noticeA1),
                                "{\"studentId\":" + studentA1 + "}"),
                        parentKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(result)).contains("\"studentId\":" + studentA1);
    }

    @Test
    @DisplayName("학부모: 읽음 확인 후 공지 상세에서 myRead=true")
    void parentNoticeDetail_myReadTrueAfterRead() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notices/{id}/read", ACADEMY_A, noticeA1), "{}"),
                        parentKeyA))
                .andExpect(status().isOk());
        MvcResult result = mockMvc.perform(withKey(
                        get("/api/rea/{a}/notices/{id}", ACADEMY_A, noticeA1), parentKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(result)).contains("\"myRead\":true");
    }

    @Test
    @DisplayName("학부모: 키패드 출결 — 본인 출석키 → 200")
    void parentKeypad_ownKey_returns200() throws Exception {
        MvcResult result = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/attendance/keypad", ACADEMY_A),
                                "{\"authKey\":\"" + parentKeyA + "\"}"),
                        parentKeyA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(result)).contains("CHECK_IN");
    }

    // ===== PARENT 차단 (phase5-task1b) — 허용되지 않은 쓰기는 403 =====

    @Test
    @DisplayName("학부모: 공지 수정·삭제 → 403")
    void parentModifyNotice_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, noticeA1),
                                "{\"title\":\"무단 수정\"}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(
                        delete("/api/rea/{a}/notices/{id}", ACADEMY_A, noticeA1), parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모: 알림 발송·발송 이력 → 403")
    void parentNotificationAdmin_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notifications/send", ACADEMY_A),
                                "{\"targetType\":\"ALL\",\"title\":\"x\",\"content\":\"y\"}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/notifications/history", ACADEMY_A), parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모: 상담 메시지 작성·스레드 종료 → 403")
    void parentConsultationWrites_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations/{id}/messages",
                                        ACADEMY_A, consultationA1),
                                "{\"studentId\":" + studentA1 + ",\"content\":\"무단 답글\"}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations/{id}/close",
                                        ACADEMY_A, consultationA1),
                                "{\"studentId\":" + studentA1 + "}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모: 출결 직접 기록 (POST /attendance/check, 강사·원장 전용) → 403")
    void parentAttendanceCheck_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/attendance/check", ACADEMY_A),
                                "{\"studentId\":" + studentA1 + ",\"eventType\":\"CHECK_IN\"}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모: 아이소식(NEWS) 위조 등록 → 403")
    void parentCreateNews_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations", ACADEMY_A),
                                "{\"studentId\":" + studentA1 + ",\"type\":\"NEWS\","
                                        + "\"title\":\"위조\",\"content\":\"위조 내용\","
                                        + "\"authorMemberId\":1}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모: 타 학생 출석키로 키패드 기록 → 403")
    void parentKeypad_otherKey_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/attendance/keypad", ACADEMY_A),
                                "{\"authKey\":\"" + parentKeyA2 + "\"}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모: 다른 학생의 상담 스레드 상세 → 조회 불가 (400)")
    void parentOtherStudentConsultation_returns400() throws Exception {
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/consultations/{id}", ACADEMY_A, consultationA2),
                        parentKeyA))
                .andExpect(status().isBadRequest());
    }

    // ===== 6단계 작업 1-2: 학부모 상담 답글·읽음 처리 403 정책 확인 (의도적 정책 유지) =====

    @Test
    @DisplayName("학부모: 상담 스레드 답글 (POST /consultations/{id}/messages) → 403 (원장·강사 전용)")
    void parentConsultationReply_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations/{id}/messages",
                                        ACADEMY_A, consultationA1),
                                "{\"content\":\"학부모 답글\",\"studentId\":" + studentA1 + "}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모: 상담 메시지 읽음 처리 (POST /consultations/{id}/messages/{mid}/read) → 403")
    void parentConsultationMessageRead_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations/{id}/messages/{mid}/read",
                                        ACADEMY_A, consultationA1, 1L),
                                "{\"studentId\":" + studentA1 + "}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모: 알림 읽음 처리 (POST /notifications/{id}/read) → 403")
    void parentNotificationMarkRead_returns403() throws Exception {
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notifications/{id}/read", ACADEMY_A, 1L),
                                "{\"studentId\":" + studentA1 + "}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모 키로 다른 학원 URL 접근·쓰기 → 403 (테넌트 격리 유지)")
    void parentCrossAcademy_returns403() throws Exception {
        mockMvc.perform(withKey(get("/api/rea/{b}/notices", ACADEMY_B), parentKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(
                        json(post("/api/rea/{b}/consultations", ACADEMY_B),
                                "{\"type\":\"INQUIRY\",\"title\":\"x\"}"),
                        parentKeyA))
                .andExpect(status().isForbidden());
    }
}
