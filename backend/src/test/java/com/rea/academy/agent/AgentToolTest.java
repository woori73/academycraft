package com.rea.academy.agent;

import com.rea.academy.agent.tools.AnalyzeGradeChangesTool;
import com.rea.academy.agent.tools.AssignStudentToClassTool;
import com.rea.academy.agent.tools.CreateNoticeDraftTool;
import com.rea.academy.agent.tools.GenerateOperationReportTool;
import com.rea.academy.agent.tools.GetAbsentStudentsTool;
import com.rea.academy.agent.tools.GetHomeworkIncompleteTool;
import com.rea.academy.agent.tools.RegisterStudentTool;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.security.AuthContext;
import com.rea.academy.service.AttendanceService;
import com.rea.academy.service.ClassService;
import com.rea.academy.service.LearningService;
import com.rea.academy.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 도구(7개) 단위 테스트 (H2 인메모리).
 * 기존 Service를 재사용하는 각 도구의 입·출력을 검증한다.
 */
@SpringBootTest
@Transactional
class AgentToolTest {

    private static final Long ACADEMY = 1L;

    @Autowired
    private RegisterStudentTool registerStudentTool;
    @Autowired
    private AssignStudentToClassTool assignStudentToClassTool;
    @Autowired
    private GetAbsentStudentsTool getAbsentStudentsTool;
    @Autowired
    private GetHomeworkIncompleteTool getHomeworkIncompleteTool;
    @Autowired
    private CreateNoticeDraftTool createNoticeDraftTool;
    @Autowired
    private AnalyzeGradeChangesTool analyzeGradeChangesTool;
    @Autowired
    private GenerateOperationReportTool generateOperationReportTool;

    @Autowired
    private MemberService memberService;
    @Autowired
    private ClassService classService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private LearningService learningService;

    private Long ownerId;
    private Long classId;
    private Long studentId;

    private AgentContext ctx;

    @BeforeEach
    void setUp() {
        ownerId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                .name("원장").phone("010-0000-0001").build()).getId();
        ctx = new AgentContext(AuthContext.PrincipalType.MEMBER, ACADEMY,
                ReaRole.ACADEMY_OWNER, ownerId, "원장", true);

        classId = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .name("수학 A반").teacherId(ownerId).build()).getId();
        studentId = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name("홍학생").grade("고2").build()).getId();
    }

    // ===== 1. registerStudent =====

    @Test
    @DisplayName("registerStudent: 학생 등록 + 인증키 반환")
    void registerStudent_createsStudent() {
        Map<String, Object> result = registerStudentTool.execute(
                Map.of("name", "김신입", "parentName", "김부모", "parentPhone", "010-1111-2222"), ctx);

        assertThat(result.get("name")).isEqualTo("김신입");
        assertThat(result.get("studentId")).isNotNull();
        assertThat(result.get("authKey")).isNotNull();
    }

    @Test
    @DisplayName("registerStudent: 반ID가 있으면 등록 직후 배정")
    void registerStudent_withClass_assigns() {
        Map<String, Object> result = registerStudentTool.execute(
                Map.of("name", "박신입", "classId", classId), ctx);

        assertThat(result.get("classId")).isEqualTo(classId);
        assertThat(result.get("className")).isEqualTo("수학 A반");
        Long newStudentId = (Long) result.get("studentId");
        assertThat(classService.getClassDetail(ACADEMY, classId).getStudents())
                .anyMatch(s -> s.getId().equals(newStudentId));
    }

    @Test
    @DisplayName("registerStudent: 이름 누락 → 실패")
    void registerStudent_missingName_fails() {
        assertThatThrownBy(() -> registerStudentTool.execute(Map.of(), ctx))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 2. assignStudentToClass =====

    @Test
    @DisplayName("assignStudentToClass: 학생을 반에 배정")
    void assignStudentToClass_assigns() {
        Map<String, Object> result = assignStudentToClassTool.execute(
                Map.of("studentId", studentId, "classId", classId), ctx);

        assertThat(result.get("studentId")).isEqualTo(studentId);
        assertThat(result.get("className")).isEqualTo("수학 A반");
        assertThat(classService.getClassDetail(ACADEMY, classId).getStudents())
                .anyMatch(s -> s.getId().equals(studentId));
    }

    // ===== 3. getAbsentStudents =====

    @Test
    @DisplayName("getAbsentStudents: 오늘 결석 학생 목록")
    void getAbsentStudents_returnsAbsent() {
        classService.assignStudents(ACADEMY, classId, List.of(studentId));
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId).eventType("ABSENT")
                .occurredAt(LocalDate.now() + "T09:00").build());

        Map<String, Object> result = getAbsentStudentsTool.execute(Map.of("date", "오늘"), ctx);

        assertThat(result.get("date")).isEqualTo(LocalDate.now().toString());
        assertThat((int) result.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> absentees = (List<Map<String, Object>>) result.get("absentees");
        assertThat(absentees.get(0).get("studentName")).isEqualTo("홍학생");
        assertThat(absentees.get(0).get("className")).isEqualTo("수학 A반");
    }

    @Test
    @DisplayName("getAbsentStudents: 결석 없으면 빈 목록")
    void getAbsentStudents_empty() {
        Map<String, Object> result = getAbsentStudentsTool.execute(Map.of(), ctx);

        assertThat((int) result.get("count")).isEqualTo(0);
    }

    // ===== 4. getHomeworkIncomplete =====

    @Test
    @DisplayName("getHomeworkIncomplete: 미제출(미완료·미검사) 학생 목록")
    void getHomeworkIncomplete_returnsIncomplete() {
        Long studentId2 = memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name("박학생").build()).getId();
        classService.assignStudents(ACADEMY, classId, List.of(studentId, studentId2));
        Long homeworkId = learningService.createHomework(ACADEMY,
                LearningDto.HomeworkCreateRequest.builder()
                        .classId(classId).title("수학 숙제").content("수학 문제집 풀이")
                        .dueDate(LocalDate.now().plusDays(1).toString()).build())
                .getHomework().getId();
        learningService.checkHomework(ACADEMY, homeworkId,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(studentId).status("COMPLETED").build());

        Map<String, Object> result = getHomeworkIncompleteTool.execute(
                Map.of("classId", classId, "homeworkId", homeworkId), ctx);

        assertThat((int) result.get("incompleteCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) result.get("students");
        assertThat(students.get(0).get("studentName")).isEqualTo("박학생");
    }

    @Test
    @DisplayName("getHomeworkIncomplete: 숙제ID 없으면 반 전체 숙제 대상")
    void getHomeworkIncomplete_withoutHomeworkId() {
        classService.assignStudents(ACADEMY, classId, List.of(studentId));
        learningService.createHomework(ACADEMY,
                LearningDto.HomeworkCreateRequest.builder()
                        .classId(classId).title("숙제1").content("내용1")
                        .dueDate(LocalDate.now().plusDays(1).toString()).build());
        learningService.createHomework(ACADEMY,
                LearningDto.HomeworkCreateRequest.builder()
                        .classId(classId).title("숙제2").content("내용2")
                        .dueDate(LocalDate.now().plusDays(2).toString()).build());

        Map<String, Object> result = getHomeworkIncompleteTool.execute(
                Map.of("classId", classId), ctx);

        assertThat((int) result.get("incompleteCount")).isEqualTo(2);
    }

    // ===== 5. createNoticeDraft =====

    @Test
    @DisplayName("createNoticeDraft: 전체 공지 초안 작성 (알림 발송 없음)")
    void createNoticeDraft_all() {
        Map<String, Object> result = createNoticeDraftTool.execute(
                Map.of("target", "ALL", "title", "방학 안내", "content", "여름방학 안내입니다."), ctx);

        assertThat(result.get("draft")).isEqualTo(true);
        assertThat((String) result.get("title")).startsWith("[초안]");
        assertThat(result.get("noticeId")).isNotNull();
    }

    @Test
    @DisplayName("createNoticeDraft: 반 대상 초안 작성")
    void createNoticeDraft_classTarget() {
        Map<String, Object> result = createNoticeDraftTool.execute(
                Map.of("target", String.valueOf(classId), "title", "반 공지", "content", "반 공지입니다."), ctx);

        assertThat(result.get("target")).isEqualTo(String.valueOf(classId));
        assertThat(result.get("noticeId")).isNotNull();
    }

    // ===== 6. analyzeGradeChanges =====

    @Test
    @DisplayName("analyzeGradeChanges: 과목별 평균·추이 분석")
    void analyzeGradeChanges_analyzes() {
        String today = LocalDate.now().toString();
        learningService.createGrade(ACADEMY, LearningDto.GradeCreateRequest.builder()
                .studentId(studentId).examName("3월 모의고사").subject("수학")
                .score(70).fullScore(100).examDate(today).build());
        learningService.createGrade(ACADEMY, LearningDto.GradeCreateRequest.builder()
                .studentId(studentId).examName("6월 모의고사").subject("수학")
                .score(85).fullScore(100).examDate(today).build());

        Map<String, Object> result = analyzeGradeChangesTool.execute(
                Map.of("studentId", studentId,
                        "startDate", LocalDate.now().minusDays(10).toString(),
                        "endDate", today), ctx);

        assertThat(result.get("studentName")).isEqualTo("홍학생");
        assertThat((int) result.get("gradeCount")).isEqualTo(2);
        assertThat((Double) result.get("overallAvgRate")).isEqualTo(77.5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subjects = (List<Map<String, Object>>) result.get("subjects");
        assertThat(subjects).hasSize(1);
        assertThat(subjects.get(0).get("subject")).isEqualTo("수학");
        assertThat(subjects.get(0).get("trend")).isEqualTo("상승");
        assertThat((String) result.get("summary")).contains("77.5%");
    }

    @Test
    @DisplayName("analyzeGradeChanges: 기록 없으면 안내 요약")
    void analyzeGradeChanges_empty() {
        Map<String, Object> result = analyzeGradeChangesTool.execute(
                Map.of("studentId", studentId), ctx);

        assertThat((int) result.get("gradeCount")).isEqualTo(0);
        assertThat((String) result.get("summary")).contains("기록이 없습니다");
    }

    // ===== 7. generateOperationReport =====

    @Test
    @DisplayName("generateOperationReport: 운영 현황 보고서 생성")
    void generateOperationReport_generates() {
        classService.assignStudents(ACADEMY, classId, List.of(studentId));
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId).eventType("CHECK_IN")
                .occurredAt(LocalDate.now() + "T09:00").build());

        Map<String, Object> result = generateOperationReportTool.execute(
                Map.of("startDate", LocalDate.now().toString(),
                        "endDate", LocalDate.now().toString()), ctx);

        assertThat(result.get("classCount")).isEqualTo(1);
        assertThat((Double) result.get("attendanceRate")).isEqualTo(100.0);
        assertThat((String) result.get("report")).contains("운영 현황 보고서");
        assertThat((String) result.get("report")).contains("출결률");
    }
}
