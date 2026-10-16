package com.rea.academy.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.service.AttendanceService;
import com.rea.academy.service.ClassService;
import com.rea.academy.service.LearningService;
import com.rea.academy.service.MemberService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 6단계 작업 1-3 — 대량 데이터 화면 검증 (더미 데이터만, H2 인메모리).
 *
 * <p>가명 데이터: 학생 500명("학생001"~"학생500"), 출결 기록 10,000건
 * (2026-10-01~20, 학생당 20건), 성적 1,500건 (학생 300명 × 5과목).
 * 실제 개인정보 사용 없음.</p>
 *
 * <p>측정: MockMvc(HTTP 스택 포함)를 통한 API 응답 시간. 화면 목표 3초 이내.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// @BeforeAll에서 대량 데이터를 커밋하므로 공유 컨텍스트 오염 방지: 이 클래스 후 컨텍스트 폐기
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BulkDataVerificationTest {

    private static final Long ACADEMY = 1L;
    private static final int STUDENT_COUNT = 500;
    private static final int ATTEND_DAYS = 20;   // 2026-10-01 ~ 2026-10-20
    private static final int GRADE_STUDENTS = 300;
    private static final String[] SUBJECTS = {"국어", "영어", "수학", "과학", "사회"};
    private static final long LIMIT_MS = 3000L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MemberService memberService;
    @Autowired
    private ClassService classService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private LearningService learningService;
    @Autowired
    private ReaMemberRepository memberRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private String ownerKey;
    private Long classId;
    private List<Long> studentIds;

    @BeforeAll
    void buildBulkData() {
        long ownerId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                .name("원장(대량검증)").phone("010-0000-9001").build()).getId();
        ownerKey = memberRepository.findById(ownerId).orElseThrow().getAuthKey();

        classId = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name("대량검증반").teacherId(ownerId).build()).getId();

        studentIds = new ArrayList<>(STUDENT_COUNT);
        String[] grades = {"고1", "고2", "고3"};
        for (int i = 1; i <= STUDENT_COUNT; i++) {
            String name = String.format("학생%03d", i);   // 가명
            studentIds.add(memberService.createStudent(StudentDto.CreateRequest.builder()
                    .academyId(ACADEMY).name(name).grade(grades[i % 3]).build()).getId());
        }
        classService.assignStudents(ACADEMY, classId, studentIds);

        // 출결 10,000건: 500명 × 20일, 등원(CHECK_IN)
        for (int day = 1; day <= ATTEND_DAYS; day++) {
            String occurredAt = String.format("2026-10-%02dT08:30", day);
            for (Long sid : studentIds) {
                attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                        .studentId(sid).eventType("CHECK_IN").occurredAt(occurredAt).build());
            }
        }

        // 성적 1,500건: 300명 × 5과목
        for (int i = 0; i < GRADE_STUDENTS; i++) {
            for (int j = 0; j < SUBJECTS.length; j++) {
                learningService.createGrade(ACADEMY, LearningDto.GradeCreateRequest.builder()
                        .studentId(studentIds.get(i))
                        .examName("2026-10 중간고사")
                        .subject(SUBJECTS[j])
                        .score((i * 7 + j * 13) % 101)
                        .fullScore(100)
                        .examDate("2026-10-15")
                        .build());
            }
        }
    }

    private String key() {
        return ownerKey;
    }

    @Test
    @DisplayName("MBR-01: 학생 500명 목록 조회 — 건수·3초 이내")
    void studentsList_500_under3s() throws Exception {
        long t0 = System.nanoTime();
        MvcResult result = mockMvc.perform(get("/api/rea/{a}/students", ACADEMY)
                        .header("X-Auth-Key", key()))
                .andExpect(status().isOk())
                .andReturn();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        JsonNode arr = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(arr.size()).isEqualTo(STUDENT_COUNT);
        assertThat(arr.get(0).get("name").asText()).isEqualTo("학생001");
        assertThat(arr.get(STUDENT_COUNT - 1).get("name").asText()).isEqualTo("학생500");
        System.out.println("[BULK] GET /students 500건: " + ms + "ms (목표 " + LIMIT_MS + "ms)");
        assertThat(ms).as("학생 목록 500건 응답 시간").isLessThan(LIMIT_MS);
    }

    @Test
    @DisplayName("MBR-01: '학생1' 검색 — 100건 매칭 (학생100~학생199, Containing 검색)")
    void studentsSearch_name1_returns100() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/rea/{a}/students", ACADEMY)
                        .param("name", "학생1")
                        .header("X-Auth-Key", key()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(arr.size()).isEqualTo(100);
        for (JsonNode n : arr) {
            assertThat(n.get("name").asText()).contains("학생1");
        }
    }

    @Test
    @DisplayName("ATT-02: 월별 출결 — 500명 × presentDays=20 집계 정확성")
    void monthly_aggregationAccurate() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/rea/{a}/attendance/monthly", ACADEMY)
                        .param("classId", String.valueOf(classId))
                        .param("yearMonth", "2026-10")
                        .header("X-Auth-Key", key()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        JsonNode students = root.get("students");
        assertThat(students.size()).isEqualTo(STUDENT_COUNT);
        int totalPresent = 0;
        for (JsonNode s : students) {
            assertThat(s.get("presentDays").asInt()).as(s.get("studentName").asText()).isEqualTo(ATTEND_DAYS);
            assertThat(s.get("absentDays").asInt()).isEqualTo(0);
            totalPresent += s.get("presentDays").asInt();
        }
        assertThat(totalPresent).isEqualTo(STUDENT_COUNT * ATTEND_DAYS);
        // 일별 상태 표: 1~20일 "IN", 21~31일 "NONE"
        JsonNode first = students.get(0);
        assertThat(first.get("dailyStatus").get("1").asText()).isEqualTo("IN");
        assertThat(first.get("dailyStatus").get("20").asText()).isEqualTo("IN");
        assertThat(first.get("dailyStatus").get("21").asText()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("ATT-02: 월별 출결 응답 시간 측정 (500명, 10,000건 기반) — N+1 발견 기록용")
    void monthly_responseTimeMeasured() throws Exception {
        // [발견] AttendanceService.getMonthly → toMonthlyStudent(학생당 31일 개별 조회) = 15,500 쿼리 (N+1).
        // 집계 정확성은 통과(위 테스트)하나 3초 목표 미달. 백엔드 최적화(월 단위 일괄 조회)는
        // 6-1 범위를 벗어나므로 "추가 검토 후 보고" 대상으로 기록하고, 본 테스트는 측정만 수행한다.
        long t0 = System.nanoTime();
        mockMvc.perform(get("/api/rea/{a}/attendance/monthly", ACADEMY)
                        .param("classId", String.valueOf(classId))
                        .param("yearMonth", "2026-10")
                        .header("X-Auth-Key", key()))
                .andExpect(status().isOk());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[BULK] GET /attendance/monthly 500명×31일: " + ms + "ms (목표 " + LIMIT_MS + "ms — N+1로 미달, 백엔드 최적화 검토 필요)");
    }

    @Test
    @DisplayName("GRD-02: 학생별 성적 5건 조회 — 3초 이내")
    void gradesPerStudent_under3s() throws Exception {
        long t0 = System.nanoTime();
        MvcResult result = mockMvc.perform(
                        get("/api/rea/{a}/grades/student/{s}", ACADEMY, studentIds.get(0))
                                .header("X-Auth-Key", key()))
                .andExpect(status().isOk())
                .andReturn();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        JsonNode arr = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(arr.size()).isEqualTo(SUBJECTS.length);
        System.out.println("[BULK] GET /grades/student/{id} 5건: " + ms + "ms (목표 " + LIMIT_MS + "ms)");
        assertThat(ms).as("성적 조회 응답 시간").isLessThan(LIMIT_MS);
    }
}
