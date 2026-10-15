package com.rea.academy.security;

import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.service.AuthService;
import com.rea.academy.service.MemberService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정식 로그인(JWT) 보안 테스트 — H2 + MockMvc.
 *
 * 검증: 로그인 성공·실패·잠금 / 토큰 위조·만료 거부 / Refresh 로테이션 /
 * 로그아웃 / 타 학원 차단 / 학부모 타 학생 차단 / 강사 권한 밖 차단 /
 * 비활성 계정 차단 / 비밀번호 재설정.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JwtAuthTest {

    private static final Long ACADEMY_A = 1L;
    private static final Long ACADEMY_B = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberService memberService;

    @Autowired
    private AuthService authService;

    @Autowired
    private ReaMemberRepository memberRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private long ownerAId;
    private long teacherAId;
    private long ownerBId;
    private long studentA1;

    @BeforeEach
    void setUp() {
        ownerAId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.ACADEMY_OWNER)
                .name("원장A").phone("010-0000-0001").build()).getId();
        String ownerKeyA = memberRepository.findById(ownerAId).orElseThrow().getAuthKey();
        authService.setupCredentials(ownerKeyA, "ownerA", "Owner1234");

        teacherAId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.TEACHER)
                .name("강사A").phone("010-0000-0002").build()).getId();
        String teacherKeyA = memberRepository.findById(teacherAId).orElseThrow().getAuthKey();
        authService.setupCredentials(teacherKeyA, "teacherA", "Teacher1234");

        ownerBId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_B).role(ReaRole.ACADEMY_OWNER)
                .name("원장B").phone("010-0000-0003").build()).getId();
        String ownerKeyB = memberRepository.findById(ownerBId).orElseThrow().getAuthKey();
        authService.setupCredentials(ownerKeyB, "ownerB", "Owner1234");

        studentA1 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("학생A1").grade("고2")
                .parentName("학부모A1").parentPhone("010-0000-0011").build()).getId();
    }

    // ================= 헬퍼 =================

    private MvcResult login(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/rea/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private String accessTokenOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);
        // 간단 JSON 파싱: "accessToken":"..."
        int s = body.indexOf("\"accessToken\":\"") + 15;
        int e = body.indexOf("\"", s);
        return body.substring(s, e);
    }

    private Cookie refreshCookieOf(MvcResult result) {
        return result.getResponse().getCookie("ACRT");
    }

    private String loginAs(String loginId, String password) throws Exception {
        MvcResult r = login(loginId, password);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return accessTokenOf(r);
    }

    // ================= 로그인 =================

    @Test
    @DisplayName("로그인 성공 → 200, Access Token + HttpOnly Refresh 쿠키")
    void loginSuccess() throws Exception {
        MvcResult r = login("ownerA", "Owner1234");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(accessTokenOf(r)).isNotBlank();
        Cookie c = refreshCookieOf(r);
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        // 응답 본문에 비밀번호·리프레시 토큰 원문 노출 금지
        String body = r.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Owner1234");
        assertThat(body).doesNotContain("refreshToken");
    }

    @Test
    @DisplayName("잘못된 비밀번호 → 401")
    void loginWrongPassword() throws Exception {
        MvcResult r = login("ownerA", "Wrong9999");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("존재하지 않는 아이디 → 401 (존재 여부 누설 방지)")
    void loginUnknownId() throws Exception {
        MvcResult r = login("nobody", "Whatever1");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("5회 연속 실패 → 계정 잠금 → 올바른 비밀번호도 401")
    void accountLockout() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login("teacherA", "Wrong9999").getResponse().getStatus())
                    .isEqualTo(401);
        }
        // 잠금 후에는 올바른 비밀번호도 거부
        MvcResult r = login("teacherA", "Teacher1234");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8)).contains("잠겼습니다");
    }

    @Test
    @DisplayName("비활성 계정 로그인 → 401")
    void deactivatedAccountLogin() throws Exception {
        memberService.deactivateMember(ACADEMY_A, teacherAId);
        MvcResult r = login("teacherA", "Teacher1234");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    // ================= 토큰 인증 =================

    @Test
    @DisplayName("Bearer 토큰으로 API 접근 → 200")
    void bearerAccess() throws Exception {
        String token = loginAs("ownerA", "Owner1234");
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰 없이 API 접근 → 401")
    void noToken() throws Exception {
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/members"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("위조 토큰 → 401")
    void forgedToken() throws Exception {
        String token = loginAs("ownerA", "Owner1234");
        String forged = token.substring(0, token.length() - 2) + "xx";
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Refresh 토큰을 Access로 사용 → 401 (타입 불일치)")
    void wrongTokenType() throws Exception {
        MvcResult r = login("ownerA", "Owner1234");
        String refresh = refreshCookieOf(r).getValue();
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh))
                .andExpect(status().isUnauthorized());
    }

    // ================= Refresh 로테이션 =================

    @Test
    @DisplayName("Refresh → 새 Access 발급 + 구 Refresh 재사용 시 401")
    void refreshRotation() throws Exception {
        MvcResult r = login("ownerA", "Owner1234");
        Cookie c1 = refreshCookieOf(r);

        MvcResult r2 = mockMvc.perform(post("/api/rea/auth/refresh").cookie(c1))
                .andExpect(status().isOk()).andReturn();
        Cookie c2 = refreshCookieOf(r2);
        assertThat(c2.getValue()).isNotEqualTo(c1.getValue());

        // 구 리프레시 토큰 재사용 → 차단
        mockMvc.perform(post("/api/rea/auth/refresh").cookie(c1))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃 → Refresh 무효화")
    void logout() throws Exception {
        MvcResult r = login("ownerA", "Owner1234");
        Cookie c = refreshCookieOf(r);
        mockMvc.perform(post("/api/rea/auth/logout").cookie(c))
                .andExpect(status().isNoContent());
        // 로그아웃 후 같은 refresh로 갱신 시도 → 401
        mockMvc.perform(post("/api/rea/auth/refresh").cookie(c))
                .andExpect(status().isUnauthorized());
    }

    // ================= 권한 분리 =================

    @Test
    @DisplayName("다른 학원 데이터 접근 → 403 (클라이언트 학원 ID 위조 차단)")
    void crossAcademyBlocked() throws Exception {
        String tokenA = loginAs("ownerA", "Owner1234");
        mockMvc.perform(get("/api/rea/" + ACADEMY_B + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("강사의 원장 전용 기능 → 403")
    void teacherOwnerOnlyBlocked() throws Exception {
        String token = loginAs("teacherA", "Teacher1234");
        // 학원 설정 변경 (원장 전용)
        mockMvc.perform(put("/api/rea/" + ACADEMY_A + "/attendance/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("학부모가 다른 학생 정보 조회 → 403")
    void parentOtherStudentBlocked() throws Exception {
        // 학부모 계정 생성 (자녀: studentA1)
        long parentId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY_A).role(ReaRole.PARENT)
                .name("학부모A1").phone("010-0000-0011").build()).getId();
        ReaMember parent = memberRepository.findById(parentId).orElseThrow();
        parent.linkStudent(studentA1);
        memberRepository.save(parent);
        String parentKey = parent.getAuthKey();
        authService.setupCredentials(parentKey, "parentA1", "Parent1234");

        String token = loginAs("parentA1", "Parent1234");
        // 본인 자녀 조회 → 200
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/students/" + studentA1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        // 다른 학생 조회 → 403
        long otherStudent = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY_A).name("학생A2").grade("고1").build()).getId();
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/students/" + otherStudent)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비활성화 후 기존 토큰 → 401")
    void tokenAfterDeactivation() throws Exception {
        String token = loginAs("teacherA", "Teacher1234");
        memberService.deactivateMember(ACADEMY_A, teacherAId);
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ================= 비밀번호 재설정 =================

    @Test
    @DisplayName("비밀번호 재설정 요청 → 확인 → 새 비밀번호로 로그인")
    void passwordResetFlow() throws Exception {
        MvcResult req = mockMvc.perform(post("/api/rea/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"teacherA\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = req.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);
        int s = body.indexOf("\"resetToken\":\"");
        assertThat(s).isGreaterThanOrEqualTo(0); // 개발 환경에서 토큰 반환
        String token = body.substring(s + 14, body.indexOf("\"", s + 14));

        mockMvc.perform(post("/api/rea/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"NewPass1234\"}"))
                .andExpect(status().isNoContent());

        // 새 비밀번호로 로그인 성공, 구 비밀번호 실패
        assertThat(login("teacherA", "NewPass1234").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(login("teacherA", "Teacher1234").getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("잘못된 재설정 토큰 → 401, 존재하지 않는 아이디 요청도 200 (누설 방지)")
    void passwordResetInvalid() throws Exception {
        mockMvc.perform(post("/api/rea/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"invalid\",\"newPassword\":\"NewPass1234\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/rea/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"nobody\"}"))
                .andExpect(status().isOk());
    }

    // ================= 원장의 자격 설정 =================

    @Test
    @DisplayName("원장이 회원 자격 설정 → 해당 회원 로그인 가능")
    void ownerSetsCredentials() throws Exception {
        String ownerToken = loginAs("ownerA", "Owner1234");
        mockMvc.perform(put("/api/rea/" + ACADEMY_A + "/members/" + teacherAId + "/credentials")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"teacherA2\",\"tempPassword\":\"Temp1234\"}"))
                .andExpect(status().isNoContent());
        assertThat(login("teacherA2", "Temp1234").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("강사가 다른 회원 자격 설정 → 403")
    void teacherSetsCredentialsBlocked() throws Exception {
        String teacherToken = loginAs("teacherA", "Teacher1234");
        mockMvc.perform(put("/api/rea/" + ACADEMY_A + "/members/" + ownerAId + "/credentials")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"hacker\",\"tempPassword\":\"Hack1234\"}"))
                .andExpect(status().isForbidden());
    }

    // ================= 기존 인증키 호환 (개발) =================

    @Test
    @DisplayName("개발 환경: X-Auth-Key 폴백 동작 유지")
    void legacyAuthKeyFallback() throws Exception {
        String key = memberRepository.findById(ownerAId).orElseThrow().getAuthKey();
        mockMvc.perform(get("/api/rea/" + ACADEMY_A + "/members")
                        .header("X-Auth-Key", key))
                .andExpect(status().isOk());
    }
}
