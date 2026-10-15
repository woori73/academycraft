package com.rea.academy.e2e;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.security.AuthKeyFilter;
import com.rea.academy.service.MemberService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REA 5단계 작업 2 — 실제 학원 운영 흐름 종단(E2E) 검증 (HTTP 레벨).
 *
 * <p>모든 시나리오는 MockMvc(실제 Spring Security 필터 체인 포함)로 수행되며,
 * 각 단계마다 실제 HTTP 상태코드와 응답 본문 핵심을 assert 한다.
 * H2 인메모리, Mock 알림 발송기(외부 발송 없음).</p>
 *
 * <p>부트스트랩 설계: 공개 회원가입 API가 없으므로 <b>첫 원장</b>은 서비스 시딩으로
 * 생성한다(운영에서는 플랫폼 관리자/배포 시딩 경로). 인증키 발급 후의 모든 흐름은
 * HTTP로 검증한다. X-Auth-Key는 발급 직후 레포지토리에서 읽어(발급 확인),
 * key-login 공개 API로 로그인한다.</p>
 *
 * <p>시나리오:
 * <ul>
 *   <li>A: 학원 운영 전체 흐름 (등록→출결→공지→상담→숙제→성적→감사 로그)</li>
 *   <li>B: 권한 검증 (401/403)</li>
 *   <li>C: 학원 격리 검증 (교차 테넌트 403)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AcademyE2eTest {

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

    // ================= 부트스트랩 (서비스 시딩 — 공개 회원가입 API 없음) =================

    private long seedOwner(long academyId, String name) {
        return memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(academyId).role(ReaRole.ACADEMY_OWNER)
                .name(name).phone("010-0000-0001").build()).getId();
    }

    private String memberKey(long memberId) {
        return memberRepository.findById(memberId).orElseThrow().getAuthKey();
    }

    // ================= 요청 헬퍼 =================

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder req, String key) {
        if (key != null) {
            req.header(AuthKeyFilter.AUTH_KEY_HEADER, key);
        }
        return req;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String bodyUtf8(MvcResult result) throws Exception {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** JSON에서 첫 번째 "id":N 추출 */
    private static long firstId(String body) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new IllegalStateException("id를 찾을 수 없음: " + body);
    }

    /** JSON에서 "authKey":"..." 추출 */
    private static String authKey(String body) {
        Matcher m = Pattern.compile("\"authKey\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalStateException("authKey를 찾을 수 없음: " + body);
    }

    private static long nestedId(String body, String parent) {
        Matcher m = Pattern.compile("\"" + parent + "\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)")
                .matcher(body);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new IllegalStateException(parent + ".id를 찾을 수 없음: " + body);
    }

    // =================================================================
    // 시나리오 A: 학원 운영 전체 흐름
    // =================================================================

    @Test
    @DisplayName("시나리오 A: 학원 운영 전체 흐름 (등록→출결→공지→상담→숙제→성적→감사)")
    void scenarioA_fullAcademyFlow() throws Exception {
        // ---- 부트스트랩: 첫 원장 (플랫폼 관리자 시딩 경로, HTTP 아님) ----
        long ownerId = seedOwner(ACADEMY_A, "김원장");
        String ownerKey = memberKey(ownerId);
        assertThat(ownerKey).isNotBlank(); // X-Auth-Key 발급 확인

        // ---- A1. 학원 생성 (관리자) ----
        MvcResult academyResult = mockMvc.perform(withKey(
                        json(put("/api/rea/{a}/info", ACADEMY_A),
                                "{\"name\":\"한빛학원\",\"phone\":\"02-1234-5678\","
                                        + "\"address\":\"서울시 강남구 학원로 10\"}"),
                        ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(academyResult)).contains("한빛학원");

        // ---- A2. 원장 인증키 로그인 (공개 API) ----
        MvcResult loginResult = mockMvc.perform(json(
                        post("/api/rea/auth/key-login"),
                        "{\"authKey\":\"" + ownerKey + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String loginBody = bodyUtf8(loginResult);
        assertThat(loginBody).contains("ACADEMY_OWNER");
        assertThat(loginBody).contains("\"memberId\":" + ownerId);

        // ---- A3. 학생 3명 등록 (각각 인증키 발급 확인) ----
        long[] studentIds = new long[3];
        String[] parentKeys = new String[3];
        String[] names = {"김철수", "이영희", "박민준"};
        for (int i = 0; i < 3; i++) {
            MvcResult r = mockMvc.perform(withKey(
                            json(post("/api/rea/{a}/students", ACADEMY_A),
                                    "{\"name\":\"" + names[i] + "\",\"grade\":\"고2\","
                                            + "\"parentName\":\"" + names[i] + "부모\","
                                            + "\"parentPhone\":\"010-1111-000" + (i + 1) + "\"}"),
                            ownerKey))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = bodyUtf8(r);
            assertThat(body).contains(names[i]);
            studentIds[i] = firstId(body);
            parentKeys[i] = authKey(body); // X-Auth-Key 발급 확인 (학부모/학생 키)
            assertThat(parentKeys[i]).isNotBlank();
        }

        // ---- A4. 강사 1명 등록 ----
        MvcResult teacherResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/members", ACADEMY_A),
                                "{\"role\":\"TEACHER\",\"name\":\"박강사\","
                                        + "\"phone\":\"010-2222-0001\"}"),
                        ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        String teacherBody = bodyUtf8(teacherResult);
        assertThat(teacherBody).contains("TEACHER");
        long teacherId = firstId(teacherBody);
        String teacherKey = memberKey(teacherId);

        // ---- A5. 반 생성 → 학생 3명 배정 ----
        MvcResult classResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/classes", ACADEMY_A),
                                "{\"name\":\"수학A반\",\"teacherId\":" + teacherId + "}"),
                        ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        long classId = firstId(bodyUtf8(classResult));

        MvcResult assignResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/classes/{c}/students", ACADEMY_A, classId),
                                "{\"studentIds\":[" + studentIds[0] + ","
                                        + studentIds[1] + "," + studentIds[2] + "]}"),
                        ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(assignResult)).contains("\"studentCount\":3");

        // ---- A6. 출결 기록: 등원 2명, 결석 1명 (출결 알림 Mock 발송 확인) ----
        for (int i = 0; i < 2; i++) {
            MvcResult r = mockMvc.perform(withKey(
                            json(post("/api/rea/{a}/attendance/check", ACADEMY_A),
                                    "{\"studentId\":" + studentIds[i]
                                            + ",\"eventType\":\"CHECK_IN\"}"),
                            teacherKey))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(bodyUtf8(r)).contains("CHECK_IN");
        }
        MvcResult absentResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/attendance/check", ACADEMY_A),
                                "{\"studentId\":" + studentIds[2]
                                        + ",\"eventType\":\"ABSENT\"}"),
                        teacherKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(absentResult)).contains("ABSENT");

        // Mock 발송 이력 확인: CHECK_IN 알림이 발송 이력 테이블에 기록됨
        MvcResult historyResult = mockMvc.perform(withKey(
                        get("/api/rea/{a}/notifications/history", ACADEMY_A), ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        String historyBody = bodyUtf8(historyResult);
        assertThat(historyBody).contains("CHECK_IN"); // Mock 발송 확인 (실제 외부 발송 없음)

        // ---- A7. 공지 작성: 전체공지 1건 + 반공지 1건 ----
        MvcResult noticeAllResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notices", ACADEMY_A),
                                "{\"title\":\"10월 휴원 안내\","
                                        + "\"content\":\"10월 3일은 휴원입니다.\","
                                        + "\"authorId\":" + ownerId + "}"),
                        ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        String noticeAllBody = bodyUtf8(noticeAllResult);
        assertThat(noticeAllBody).contains("10월 휴원 안내");
        long noticeAllId = nestedId(noticeAllBody, "notice");

        MvcResult noticeClassResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notices", ACADEMY_A),
                                "{\"classId\":" + classId + ",\"title\":\"수학A반 보강 안내\","
                                        + "\"content\":\"토요일 보강 수업이 있습니다.\","
                                        + "\"authorId\":" + ownerId + "}"),
                        ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(noticeClassResult)).contains("수학A반 보강 안내");

        // ---- A8. 학부모 로그인(학생 인증키) → 공지 목록 확인 → 읽음 확인 ----
        MvcResult parentLoginResult = mockMvc.perform(json(
                        post("/api/rea/auth/key-login"),
                        "{\"authKey\":\"" + parentKeys[0] + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String parentLoginBody = bodyUtf8(parentLoginResult);
        assertThat(parentLoginBody).contains("PARENT");
        assertThat(parentLoginBody).contains("\"studentId\":" + studentIds[0]);

        MvcResult noticeListResult = mockMvc.perform(withKey(
                        get("/api/rea/{a}/notices", ACADEMY_A), parentKeys[0]))
                .andExpect(status().isOk())
                .andReturn();
        String noticeListBody = bodyUtf8(noticeListResult);
        assertThat(noticeListBody).contains("10월 휴원 안내");   // 전체공지
        assertThat(noticeListBody).contains("수학A반 보강 안내"); // 반공지 (자녀 소속 반)

        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notices/{id}/read", ACADEMY_A, noticeAllId), "{}"),
                        parentKeys[0]))
                .andExpect(status().isOk());

        MvcResult readCheckResult = mockMvc.perform(withKey(
                        get("/api/rea/{a}/notices/{id}", ACADEMY_A, noticeAllId), parentKeys[0]))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(readCheckResult)).contains("\"myRead\":true");

        // ---- A9. 상담: 학부모 문의 등록 → 원장 답변 등록 → 학부모 답변 확인 ----
        MvcResult consultResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations", ACADEMY_A),
                                "{\"studentId\":" + studentIds[0] + ",\"type\":\"INQUIRY\","
                                        + "\"title\":\"숙제 문의\",\"content\":\"숙제가 너무 많아요.\"}"),
                        parentKeys[0]))
                .andExpect(status().isOk())
                .andReturn();
        String consultBody = bodyUtf8(consultResult);
        assertThat(consultBody).contains("\"status\":\"OPEN\"");
        long consultationId = nestedId(consultBody, "consultation");

        MvcResult replyResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations/{id}/messages",
                                        ACADEMY_A, consultationId),
                                "{\"memberId\":" + ownerId
                                        + ",\"content\":\"숙제량을 조절해 드리겠습니다.\"}"),
                        ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(replyResult)).contains("숙제량을 조절해 드리겠습니다.");

        MvcResult consultDetailResult = mockMvc.perform(withKey(
                        get("/api/rea/{a}/consultations/{id}", ACADEMY_A, consultationId),
                        parentKeys[0]))
                .andExpect(status().isOk())
                .andReturn();
        String consultDetailBody = bodyUtf8(consultDetailResult);
        assertThat(consultDetailBody).contains("숙제량을 조절해 드리겠습니다."); // 학부모 답변 확인
        assertThat(consultDetailBody).contains("ANSWERED");

        // ---- A10. 숙제 등록 (강사) → 미제출 확인 ----
        MvcResult hwResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/homework", ACADEMY_A),
                                "{\"classId\":" + classId + ",\"title\":\"수학 문제집 p.30\","
                                        + "\"content\":\"30페이지 풀기\","
                                        + "\"dueDate\":\"2026-10-16\"}"),
                        teacherKey))
                .andExpect(status().isOk())
                .andReturn();
        String hwBody = bodyUtf8(hwResult);
        assertThat(hwBody).contains("수학 문제집 p.30");
        long homeworkId = nestedId(hwBody, "homework");

        MvcResult checksResult = mockMvc.perform(withKey(
                        get("/api/rea/{a}/homework/{id}/checks", ACADEMY_A, homeworkId),
                        teacherKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(checksResult).trim()).isEqualTo("[]"); // 전원 미제출

        // ---- A11. 성적 입력 (강사) → 학습 리포트 생성 확인 (학부모) ----
        MvcResult gradeResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/grades", ACADEMY_A),
                                "{\"studentId\":" + studentIds[0]
                                        + ",\"examName\":\"10월 모의고사\",\"subject\":\"수학\","
                                        + "\"score\":85,\"fullScore\":100}"),
                        teacherKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(bodyUtf8(gradeResult)).contains("\"score\":85");

        MvcResult reportResult = mockMvc.perform(withKey(
                        get("/api/rea/{a}/reports/student/{s}", ACADEMY_A, studentIds[0]),
                        parentKeys[0]))
                .andExpect(status().isOk())
                .andReturn();
        String reportBody = bodyUtf8(reportResult);
        assertThat(reportBody).contains("gradeSummary");
        assertThat(reportBody).contains("\"gradeCount\":1");
        assertThat(reportBody).contains("85"); // 평균 85.0

        // ---- A12. 감사 로그에 주요 작업 기록 확인 ----
        MvcResult auditResult = mockMvc.perform(withKey(
                        get("/api/rea/{a}/audit-logs", ACADEMY_A), ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        String auditBody = bodyUtf8(auditResult);
        assertThat(auditBody).contains("ACADEMY_INFO_UPDATED"); // 학원 생성
        assertThat(auditBody).contains("STUDENT_CREATED");      // 학생 등록
        assertThat(auditBody).contains("CLASS_CREATED");        // 반 생성
        assertThat(auditBody).contains("NOTICE_CREATED");       // 공지 작성
    }

    // =================================================================
    // 시나리오 B: 권한 검증
    // =================================================================

    @Test
    @DisplayName("시나리오 B: 권한 검증 (401·403)")
    void scenarioB_permissions() throws Exception {
        long ownerId = seedOwner(ACADEMY_A, "김원장");
        String ownerKey = memberKey(ownerId);
        long teacherId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.TEACHER)
                .name("박강사").phone("010-2222-0001").build()).getId();
        String teacherKey = memberKey(teacherId);
        long s1 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("김철수").grade("고2")
                .parentName("김부모").parentPhone("010-1111-0001").build()).getId();
        long s2 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("이영희").grade("고2")
                .parentName("이부모").parentPhone("010-1111-0002").build()).getId();
        String parentKey1 = studentRepository.findById(s1).orElseThrow().getAuthKey();

        // B1. 학부모가 다른 학생 정보 조회 시도 → 403
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/students/{s}", ACADEMY_A, s2), parentKey1))
                .andExpect(status().isForbidden());

        // B2. 강사가 원장 전용 API 호출 (학원 설정 변경) → 403
        mockMvc.perform(withKey(
                        json(put("/api/rea/{a}/info", ACADEMY_A),
                                "{\"name\":\"무단 변경\"}"),
                        teacherKey))
                .andExpect(status().isForbidden());

        // B3. 인증키 없이 API 호출 → 401
        mockMvc.perform(get("/api/rea/{a}/students", ACADEMY_A))
                .andExpect(status().isUnauthorized());

        // B4. 학부모가 공지 작성 시도 → 403
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/notices", ACADEMY_A),
                                "{\"title\":\"무단 공지\",\"content\":\"차단되어야 함\"}"),
                        parentKey1))
                .andExpect(status().isForbidden());
    }

    // =================================================================
    // 시나리오 C: 학원 격리 검증
    // =================================================================

    @Test
    @DisplayName("시나리오 C: 학원 격리 검증 (교차 테넌트 403)")
    void scenarioC_tenantIsolation() throws Exception {
        // 학원A: 원장A + 학생A
        long ownerA = seedOwner(ACADEMY_A, "원장A");
        String keyA = memberKey(ownerA);
        long studentA = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("학생A").grade("고2").build()).getId();

        // 학원B: 원장B + 학생B
        long ownerB = seedOwner(ACADEMY_B, "원장B");
        String keyB = memberKey(ownerB);
        long studentB = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_B).name("학생B").grade("고1").build()).getId();

        // C2. 학원A 인증키로 학원B 데이터 접근 시도 → 403
        mockMvc.perform(withKey(
                        get("/api/rea/{b}/students", ACADEMY_B), keyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(
                        get("/api/rea/{b}/students/{s}", ACADEMY_B, studentB), keyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(
                        json(post("/api/rea/{b}/notices", ACADEMY_B),
                                "{\"title\":\"침투\",\"content\":\"차단\"}"),
                        keyA))
                .andExpect(status().isForbidden());

        // C3. 학원B 인증키로 학원A 데이터 접근 시도 → 403
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/students", ACADEMY_A), keyB))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/students/{s}", ACADEMY_A, studentA), keyB))
                .andExpect(status().isForbidden());

        // 정상 대조: 본인 학원 접근은 200
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/students", ACADEMY_A), keyA))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(
                        get("/api/rea/{b}/students", ACADEMY_B), keyB))
                .andExpect(status().isOk());
    }

    // =================================================================
    // 시나리오 D: 권한 매트릭스 회귀 (phase5-task1b·task2 수정분)
    // =================================================================

    @Test
    @DisplayName("시나리오 D: 권한 매트릭스 회귀 — 강사 학습 쓰기·상담 메시지, 학부모 학습 읽기")
    void scenarioD_permissionMatrixRegression() throws Exception {
        long ownerId = seedOwner(ACADEMY_A, "김원장");
        String ownerKey = memberKey(ownerId);
        long teacherId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.TEACHER)
                .name("박강사").phone("010-2222-0001").build()).getId();
        String teacherKey = memberKey(teacherId);
        long s1 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("김철수").grade("고2")
                .parentName("김부모").parentPhone("010-1111-0001").build()).getId();
        String parentKey = studentRepository.findById(s1).orElseThrow().getAuthKey();

        // 반 생성 (원장) — 숙제 등록에 필요
        MvcResult classResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/classes", ACADEMY_A),
                                "{\"name\":\"수학A반\",\"teacherId\":" + teacherId + "}"),
                        ownerKey))
                .andExpect(status().isOk())
                .andReturn();
        long classId = firstId(bodyUtf8(classResult));

        // 강사: 숙제 등록 (docs/security.md 3.2 — 숙제 쓰기 허용)
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/homework", ACADEMY_A),
                                "{\"classId\":" + classId + ",\"title\":\"숙제1\","
                                        + "\"content\":\"내용\",\"dueDate\":\"2026-10-16\"}"),
                        teacherKey))
                .andExpect(status().isOk());

        // 강사: 성적 입력 (쓰기 허용)
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/grades", ACADEMY_A),
                                "{\"studentId\":" + s1 + ",\"examName\":\"시험\","
                                        + "\"subject\":\"수학\",\"score\":90,\"fullScore\":100}"),
                        teacherKey))
                .andExpect(status().isOk());

        // 강사: 진도 기록 (쓰기 허용)
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/progress", ACADEMY_A),
                                "{\"studentId\":" + s1 + ",\"unit\":\"3단원\","
                                        + "\"progressRate\":60}"),
                        teacherKey))
                .andExpect(status().isOk());

        // 강사: 상담 개설 → 메시지 작성 (쓰기 허용)
        MvcResult consultResult = mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations", ACADEMY_A),
                                "{\"studentId\":" + s1 + ",\"type\":\"INQUIRY\","
                                        + "\"title\":\"문의\",\"content\":\"내용\"}"),
                        teacherKey))
                .andExpect(status().isOk())
                .andReturn();
        long consultationId = nestedId(bodyUtf8(consultResult), "consultation");
        mockMvc.perform(withKey(
                        json(post("/api/rea/{a}/consultations/{id}/messages",
                                        ACADEMY_A, consultationId),
                                "{\"memberId\":" + teacherId + ",\"content\":\"답변\"}"),
                        teacherKey))
                .andExpect(status().isOk());

        // 강사: 출결 설정 변경 (원장 전용) → 403
        mockMvc.perform(withKey(
                        json(put("/api/rea/{a}/attendance/settings", ACADEMY_A),
                                "{\"checkInStart\":\"08:00\"}"),
                        teacherKey))
                .andExpect(status().isForbidden());

        // 학부모: 본인 자녀 진도·성적·리포트 읽기 → 200
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/progress/student/{s}", ACADEMY_A, s1), parentKey))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/grades/student/{s}", ACADEMY_A, s1), parentKey))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(
                        get("/api/rea/{a}/reports/student/{s}", ACADEMY_A, s1), parentKey))
                .andExpect(status().isOk());
    }
}
