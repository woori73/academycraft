package com.rea.academy.perf;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 6단계 작업 4(4-4.1) — 주요 API 응답시간 참고치 측정 (H2 인메모리, MockMvc HTTP 스택 포함).
 *
 * <p>가명 데이터: 학생 300명("학생001"~"학생300"), 출결 4,500건
 * (15일 × 300명, CHECK_IN), 성적 1,000건(200명 × 5과목),
 * 반별 숙제 5건 + 대상 학생 검사 5건 + 진도 기록 10건.</p>
 *
 * <p>각 측정: 100회 반복 후 p50/p95 산출. 월별 출결(ATT-02)은 6-1에서
 * N+1(15,500 쿼리)로 이미 문서화되어 재측정에서 제외.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerfReferenceTest {

    // 학원 1·2는 다른 테스트(BulkDataVerificationTest @BeforeAll 커밋 등)가 사용하므로
    // 단일 JVM 전체 실행 시 "이미 원장이 등록되어 있습니다" 충돌을 피하기 위해 9 사용.
    // (6-1 기존 스위트의 테스트 격리 이슈 — 본 문서 4-5 및 phase6-task4-ops.md 참고)
    private static final Long ACADEMY = 9L;
    private static final int STUDENT_COUNT = 300;
    private static final int ATTEND_DAYS = 15;   // 2026-10-01 ~ 2026-10-15
    private static final int GRADE_STUDENTS = 200;
    private static final int ITERATIONS = 100;
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

    private String ownerKey;
    private Long targetStudentId;

    @BeforeAll
    void buildData() {
        long ownerId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                .name("원장(참고치측정)").phone("010-0000-9002").build()).getId();
        ownerKey = memberRepository.findById(ownerId).orElseThrow().getAuthKey();

        long classId = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name("참고치측정반").teacherId(ownerId).build()).getId();

        List<Long> studentIds = new ArrayList<>(STUDENT_COUNT);
        for (int i = 1; i <= STUDENT_COUNT; i++) {
            studentIds.add(memberService.createStudent(StudentDto.CreateRequest.builder()
                    .academyId(ACADEMY).name(String.format("학생%03d", i)).grade("고2").build()).getId());
        }
        classService.assignStudents(ACADEMY, classId, studentIds);
        targetStudentId = studentIds.get(0);

        for (int day = 1; day <= ATTEND_DAYS; day++) {
            String occurredAt = String.format("2026-10-%02dT08:30", day);
            for (Long sid : studentIds) {
                attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                        .studentId(sid).eventType("CHECK_IN").occurredAt(occurredAt).build());
            }
        }

        for (int i = 0; i < GRADE_STUDENTS; i++) {
            for (int j = 0; j < SUBJECTS.length; j++) {
                learningService.createGrade(ACADEMY, LearningDto.GradeCreateRequest.builder()
                        .studentId(studentIds.get(i))
                        .examName("2026-10 중간고사").subject(SUBJECTS[j])
                        .score((i * 7 + j * 13) % 101).fullScore(100)
                        .examDate("2026-10-15").build());
            }
        }

        // 리포트 집계 경로(숙제·검사·진도) 데이터
        List<Long> hwIds = new ArrayList<>();
        for (int h = 1; h <= 5; h++) {
            hwIds.add(learningService.createHomework(ACADEMY,
                    LearningDto.HomeworkCreateRequest.builder()
                            .classId(classId).title("숙제" + h).content("내용" + h)
                            .dueDate("2026-10-20").build()).getHomework().getId());
        }
        for (Long hwId : hwIds) {
            learningService.checkHomework(ACADEMY, hwId,
                    LearningDto.HomeworkCheckRequest.builder()
                            .studentId(targetStudentId).status("COMPLETED").score(90).build());
        }
        for (int p = 1; p <= 10; p++) {
            learningService.recordProgress(ACADEMY,
                    LearningDto.ProgressRecordRequest.builder()
                            .studentId(targetStudentId).unit("단원" + p).progressRate(p * 10)
                            .recordedDate(String.format("2026-10-%02d", p)).build());
        }
    }

    private double[] measure(MeasuredCall call) throws Exception {
        double[] samples = new double[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            call.run();
            samples[i] = (System.nanoTime() - t0) / 1_000_000.0;
        }
        Arrays.sort(samples);
        double p50 = samples[(int) (0.50 * ITERATIONS)];
        double p95 = samples[(int) (0.95 * ITERATIONS)];
        return new double[]{p50, p95};
    }

    @FunctionalInterface
    private interface MeasuredCall {
        void run() throws Exception;
    }

    @Test
    @DisplayName("학생 목록 조회 100회 — p50/p95")
    void studentsList_p50p95() throws Exception {
        double[] q = measure(() -> mockMvc.perform(get("/api/rea/{a}/students", ACADEMY)
                        .header("X-Auth-Key", ownerKey))
                .andExpect(status().isOk()));
        System.out.printf("[PERF-REF] GET /students (%d건) 100회: p50=%.1fms p95=%.1fms (목표 %dms)%n",
                STUDENT_COUNT, q[0], q[1], LIMIT_MS);
        assertThat(q[1]).as("학생 목록 p95").isLessThan(LIMIT_MS);
    }

    @Test
    @DisplayName("성적 조회 100회 — p50/p95")
    void grades_p50p95() throws Exception {
        double[] q = measure(() -> mockMvc.perform(
                        get("/api/rea/{a}/grades/student/{s}", ACADEMY, targetStudentId)
                                .header("X-Auth-Key", ownerKey))
                .andExpect(status().isOk()));
        System.out.printf("[PERF-REF] GET /grades/student/{id} (5건) 100회: p50=%.1fms p95=%.1fms (목표 %dms)%n",
                q[0], q[1], LIMIT_MS);
        assertThat(q[1]).as("성적 조회 p95").isLessThan(LIMIT_MS);
    }

    @Test
    @DisplayName("리포트 생성 100회 — p50/p95")
    void report_p50p95() throws Exception {
        double[] q = measure(() -> mockMvc.perform(
                        get("/api/rea/{a}/reports/student/{s}", ACADEMY, targetStudentId)
                                .header("X-Auth-Key", ownerKey))
                .andExpect(status().isOk()));
        System.out.printf("[PERF-REF] GET /reports/student/{id} (집계) 100회: p50=%.1fms p95=%.1fms (목표 %dms)%n",
                q[0], q[1], LIMIT_MS);
        assertThat(q[1]).as("리포트 생성 p95").isLessThan(LIMIT_MS);
    }
}
