package com.rea.academy.agent;

import com.rea.academy.common.ReaTime;
import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.domain.admin.ReaAuditLogRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.notice.ReaNoticeRepository;
import com.rea.academy.domain.notification.ReaNotificationRepository;
import com.rea.academy.dto.AgentDto;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REA 5단계 작업 3: AI 학원 비서 검증 (docs/phase5-task3-agent.md).
 *
 * 실제 DB(H2 인메모리) 상태로 8개 케이스를 검증한다:
 * 1. "영어반 학생 목록 보여줘" → 학생 목록 즉시 반환
 * 2. "오늘 결석한 학생 누구야?" → 결석자 목록 (0명 표시도 정상)
 * 3. "숙제 안 낸 학생 찾아줘" → 미제출자 목록
 * 4. "학부모 공지문 작성해줘: ..." → 초안 + 승인 요청 UI(requiresApproval), 즉시 발송 없음
 * 5. 승인 전 스냅샷 → 승인 → DB 공지 반영 + 감사 로그
 * 6. 승인 거부 시 DB 변경 없음
 * 7. "이번 달 성적 변화 분석해줘" → 분석 결과 (데이터 없으면 빈 결과 안내)
 * 8. "학생 삭제해줘" → 파괴적 작업 거부 (실행 없음)
 */
@SpringBootTest
@Transactional
class AgentTask3VerificationTest {

    private static final Long ACADEMY = 1L;

    @Autowired
    private AgentExecutor agentExecutor;
    @Autowired
    private CommandParser commandParser;
    @Autowired
    private MemberService memberService;
    @Autowired
    private ClassService classService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private LearningService learningService;
    @Autowired
    private ReaNoticeRepository noticeRepository;
    @Autowired
    private ReaNotificationRepository notificationRepository;
    @Autowired
    private ReaAuditLogRepository auditLogRepository;
    @Autowired
    private com.rea.academy.agent.plan.AgentPlanService planService;
    @Autowired
    private com.rea.academy.domain.admin.ReaAcademyRepository academyRepository;

    private AgentContext ownerCtx;
    private Long englishClassId;
    private Long kimId;   // 김철수 (영어반, 오늘 결석, 숙제 미제출, 성적 있음)
    private Long leeId;   // 이영희 (영어반, 숙제 완료, 성적 없음)
    private Long parkId;  // 박민수 (수학반)
    private Long choiId;  // 최지훈 (미배정)

    @BeforeEach
    void setUp() {
        if (academyRepository.findById(ACADEMY).isEmpty()) {
            academyRepository.save(com.rea.academy.domain.admin.ReaAcademy.builder()
                    .id(ACADEMY).name("테스트학원").active(true).build());
        }
        Long ownerId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                .name("원장").phone("010-0000-0001").build()).getId();
        ownerCtx = new AgentContext(AuthContext.PrincipalType.MEMBER, ACADEMY,
                ReaRole.ACADEMY_OWNER, ownerId, "원장", false);

        englishClassId = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .name("영어반").teacherId(ownerId).build()).getId();
        Long mathClassId = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .name("수학반").teacherId(ownerId).build()).getId();

        kimId = createStudent("김철수", "고2");
        leeId = createStudent("이영희", "고1");
        parkId = createStudent("박민수", "중3");
        choiId = createStudent("최지훈", "고2");
        classService.assignStudents(ACADEMY, englishClassId, List.of(kimId, leeId));
        classService.assignStudents(ACADEMY, mathClassId, List.of(parkId));

        // 오늘 결석: 김철수
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(kimId).eventType("ABSENT")
                .occurredAt(ReaTime.today() + "T09:00").note("감기").build());

        // 숙제: 영어반 "영어 단어 암기" — 이영희만 완료, 김철수는 미제출
        Long homeworkId = learningService.createHomework(ACADEMY,
                LearningDto.HomeworkCreateRequest.builder()
                        .classId(englishClassId).title("영어 단어 암기").content("Day 1~10 암기")
                        .dueDate(ReaTime.today().plusDays(1).toString()).build())
                .getHomework().getId();
        learningService.checkHomework(ACADEMY, homeworkId,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(leeId).status("COMPLETED").build());

        // 성적: 김철수 영어 70% → 85% (상승), 수학 80% 단일 (유지)
        createGrade(kimId, "10월 모의고사", "영어", 70, 100, ReaTime.today().minusDays(30));
        createGrade(kimId, "11월 모의고사", "영어", 85, 100, ReaTime.today());
        createGrade(kimId, "10월 모의고사", "수학", 80, 100, ReaTime.today().minusDays(20));
    }

    private Long createStudent(String name, String grade) {
        return memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name(name).grade(grade).build()).getId();
    }

    private void createGrade(Long studentId, String examName, String subject,
                             int score, int fullScore, LocalDate examDate) {
        learningService.createGrade(ACADEMY, LearningDto.GradeCreateRequest.builder()
                .studentId(studentId).examName(examName).subject(subject)
                .score(score).fullScore(fullScore)
                .examDate(examDate.toString()).build());
    }

    private AgentContext approved(AgentContext base) {
        return new AgentContext(base.principalType(), base.academyId(), base.role(),
                base.principalId(), base.principalName(), true);
    }

    private int planCreatedAuditCount() {
        return auditLogRepository
                .findByAcademyIdAndActionTypeOrderByIdDesc(ACADEMY, AuditActionType.AGENT_PLAN_CREATED).size();
    }

    private int auditCount() {
        return auditLogRepository
                .findByAcademyIdAndActionTypeOrderByIdDesc(ACADEMY, AuditActionType.AGENT_TOOL_EXECUTED)
                .size();
    }

    private int noticeCount() {
        return noticeRepository.findByAcademyIdAndActiveTrueOrderByIdDesc(ACADEMY).size();
    }

    // ===== 파서 단위 검증 =====

    @ParameterizedTest
    @CsvSource({
            "영어반 학생 목록 보여줘, getStudents",
            "학생 목록 보여줘, getStudents",
            "2반 학생 목록, getStudents",
            "숙제 안 낸 학생 찾아줘, getHomeworkIncomplete",
            "영어반 숙제 안 낸 학생 찾아줘, getHomeworkIncomplete",
            "숙제 못 낸 학생 확인, getHomeworkIncomplete",
            "오늘 결석한 학생 누구야?, getAbsentStudents",
            "학생 5 이번 달 성적 변화 분석해줘, analyzeGradeChanges",
    })
    @DisplayName("케이스 1·2·3·7: 명령 → 도구 매핑")
    void parse_mapsCaseCommands(String command, String expectedTool) {
        CommandParser.ParseResult result = commandParser.parse(command);

        assertThat(result.ok()).isTrue();
        assertThat(result.toolName()).isEqualTo(expectedTool);
    }

    @Test
    @DisplayName("케이스 1: '영어반' 반 이름 추출")
    void parse_case1_extractsClassName() {
        CommandParser.ParseResult result = commandParser.parse("영어반 학생 목록 보여줘");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("className", "영어반");
    }

    @Test
    @DisplayName("케이스 3: '영어반' 반 이름 추출 (숙제)")
    void parse_case3_extractsClassName() {
        CommandParser.ParseResult result = commandParser.parse("영어반 숙제 안 낸 학생 찾아줘");

        assertThat(result.ok()).isTrue();
        assertThat(result.params()).containsEntry("className", "영어반");
    }

    @Test
    @DisplayName("케이스 4: 공지 제목·내용 추출")
    void parse_case4_extractsTitleContent() {
        CommandParser.ParseResult result = commandParser.parse(
                "학부모 공지문 작성해줘: 제목: 내일 휴원 안내 내용: 내일은 하루 휴원합니다.");

        assertThat(result.ok()).isTrue();
        assertThat(result.toolName()).isEqualTo("createNoticeDraft");
        assertThat(result.params()).containsEntry("target", "ALL");
        assertThat(result.params()).containsEntry("title", "내일 휴원 안내");
        assertThat(result.params()).containsEntry("content", "내일은 하루 휴원합니다.");
    }

    @Test
    @DisplayName("케이스 8: '학생 삭제해줘' → 파괴적 작업 명시적 거부")
    void parse_case8_destructiveRejected() {
        CommandParser.ParseResult result = commandParser.parse("학생 삭제해줘");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("파괴적 작업");
    }

    @Test
    @DisplayName("케이스 8: ID 포함 파괴적 명령도 거부")
    void parse_case8_destructiveWithIdRejected() {
        CommandParser.ParseResult result = commandParser.parse("학생 5를 2반에서 제거해줘");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("파괴적 작업");
    }

    // ===== 케이스 1: 학생 목록 조회 =====

    @Test
    @DisplayName("케이스 1: '영어반 학생 목록 보여줘' → 즉시 반환")
    void case1_listStudentsOfClass() {
        AgentDto.ExecuteResponse resp =
                agentExecutor.execute(ownerCtx, "영어반 학생 목록 보여줘");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getToolUsed()).isEqualTo("getStudents");
        assertThat(resp.getRequiresApproval()).isNotEqualTo(Boolean.TRUE);

        Map<String, Object> result = resp.getResult();
        assertThat(result.get("className")).isEqualTo("영어반");
        assertThat((int) result.get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) result.get("students");
        assertThat(students).extracting(s -> s.get("studentName"))
                .containsExactlyInAnyOrder("김철수", "이영희");
        // 민감 정보 미포함
        assertThat(students).allMatch(s -> !s.containsKey("parentPhone") && !s.containsKey("authKey"));
    }

    @Test
    @DisplayName("케이스 1: 반 지정 없으면 학원 전체 목록")
    void case1_listAllStudents() {
        AgentDto.ExecuteResponse resp = agentExecutor.execute(ownerCtx, "학생 목록 보여줘");

        assertThat(resp.isSuccess()).isTrue();
        assertThat((int) resp.getResult().get("count")).isEqualTo(4);
    }

    @Test
    @DisplayName("케이스 1: 존재하지 않는 반 → 실패 안내")
    void case1_unknownClass_fails() {
        AgentDto.ExecuteResponse resp =
                agentExecutor.execute(ownerCtx, "과학반 학생 목록 보여줘");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).contains("반을 찾을 수 없습니다");
    }

    // ===== 케이스 2: 결석 조회 =====

    @Test
    @DisplayName("케이스 2: '오늘 결석한 학생 누구야?' → 결석자 반환")
    void case2_absentToday() {
        AgentDto.ExecuteResponse resp =
                agentExecutor.execute(ownerCtx, "오늘 결석한 학생 누구야?");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getToolUsed()).isEqualTo("getAbsentStudents");
        assertThat(resp.getResult().get("date")).isEqualTo(ReaTime.today().toString());
        assertThat((int) resp.getResult().get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> absentees =
                (List<Map<String, Object>>) resp.getResult().get("absentees");
        assertThat(absentees.get(0).get("studentName")).isEqualTo("김철수");
        assertThat(absentees.get(0).get("className")).isEqualTo("영어반");
    }

    @Test
    @DisplayName("케이스 2: 결석 데이터 없으면 0명 표시 (정상)")
    void case2_absentYesterday_zeroIsNormal() {
        AgentDto.ExecuteResponse resp =
                agentExecutor.execute(ownerCtx, "어제 결석한 학생 누구야?");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getToolUsed()).isEqualTo("getAbsentStudents");
        assertThat((int) resp.getResult().get("count")).isEqualTo(0);
    }

    // ===== 케이스 3: 숙제 미제출 =====

    @Test
    @DisplayName("케이스 3: '영어반 숙제 안 낸 학생 찾아줘' → 미제출자 반환")
    void case3_homeworkIncomplete() {
        AgentDto.ExecuteResponse resp =
                agentExecutor.execute(ownerCtx, "영어반 숙제 안 낸 학생 찾아줘");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getToolUsed()).isEqualTo("getHomeworkIncomplete");
        assertThat((int) resp.getResult().get("incompleteCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students =
                (List<Map<String, Object>>) resp.getResult().get("students");
        assertThat(students.get(0).get("studentName")).isEqualTo("김철수");
        assertThat(students.get(0).get("homeworkTitle")).isEqualTo("영어 단어 암기");
    }

    @Test
    @DisplayName("케이스 3: 반 미지정 시 실행 실패 안내 (도구 입력 부족)")
    void case3_noClass_failsWithGuidance() {
        AgentDto.ExecuteResponse resp =
                agentExecutor.execute(ownerCtx, "숙제 안 낸 학생 찾아줘");

        // 파싱은 성공하지만 반을 특정할 수 없어 도구 실행 단계에서 안내
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getToolUsed()).isEqualTo("getHomeworkIncomplete");
        assertThat(resp.getMessage()).contains("반");
    }

    // ===== 케이스 4: 공지 초안 — 승인 요청 (미승인 시 미실행) =====

    @Test
    @DisplayName("케이스 4: 승인 없으면 requiresApproval + DB 변경 없음 + 발송 없음")
    void case4_noticeDraft_requiresApproval() {
        int noticesBefore = noticeCount();
        int notificationsBefore = notificationRepository
                .findByAcademyIdOrderByCreatedAtDesc(ACADEMY).size();
        int auditsBefore = planCreatedAuditCount();

        AgentDto.ExecuteResponse resp = agentExecutor.execute(ownerCtx,
                "학부모 공지문 작성해줘: 제목: 내일 휴원 안내 내용: 내일은 하루 휴원합니다.");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getToolUsed()).isEqualTo("createNoticeDraft");
        assertThat(resp.getRequiresApproval()).isTrue();
        assertThat(resp.getPlan().getStatus()).isEqualTo("PENDING");

        // DB 변경 없음
        assertThat(noticeCount()).isEqualTo(noticesBefore);
        // 알림 발송 없음
        assertThat(notificationRepository.findByAcademyIdOrderByCreatedAtDesc(ACADEMY).size())
                .isEqualTo(notificationsBefore);
        // 계획 생성은 감사 로그에 기록 (입력 원문이 아니라 해시 요약)
        assertThat(planCreatedAuditCount()).isEqualTo(auditsBefore + 1);
    }

    // ===== 케이스 5: 계획 승인 → DB 반영 + 감사 로그 =====

    @Test
    @DisplayName("케이스 5: 계획 승인 후 공지 초안 DB 반영 + 감사 로그 (writeApproved 불리언이 아닌 서버 승인)")
    void case5_noticeDraft_approved_persistsAndAudits() {
        int noticesBefore = noticeCount();
        int notificationsBefore = notificationRepository
                .findByAcademyIdOrderByCreatedAtDesc(ACADEMY).size();

        AgentDto.ExecuteResponse requested = agentExecutor.execute(ownerCtx,
                "학부모 공지문 작성해줘: 제목: 내일 휴원 안내 내용: 내일은 하루 휴원합니다.");
        assertThat(requested.getRequiresApproval()).isTrue();
        assertThat(noticeCount()).isEqualTo(noticesBefore); // 계획 단계에서는 저장되지 않는다

        AgentDto.PlanView done = planService.approve(ownerCtx, requested.getPlan().getPlanId(),
                requested.getPlan().getPlanHash(), "DIRECT");

        assertThat(done.getStatus()).isEqualTo("EXECUTED");
        assertThat(done.getResult().get("draft")).isEqualTo(true);

        // DB에 초안 공지 반영 (제목에 [초안] 접두어)
        assertThat(noticeCount()).isEqualTo(noticesBefore + 1);
        var saved = noticeRepository.findByAcademyIdAndActiveTrueOrderByIdDesc(ACADEMY).get(0);
        assertThat(saved.getTitle()).isEqualTo("[초안] 내일 휴원 안내");
        assertThat(saved.getContent()).isEqualTo("내일은 하루 휴원합니다.");
        assertThat(saved.getClassId()).isNull(); // 전체 대상
        // 알림은 발송되지 않음 (초안 전용)
        assertThat(notificationRepository.findByAcademyIdOrderByCreatedAtDesc(ACADEMY).size())
                .isEqualTo(notificationsBefore);

        // 감사 로그에 실행 기록
        var logs = auditLogRepository
                .findByAcademyIdAndActionTypeOrderByIdDesc(ACADEMY, AuditActionType.AGENT_PLAN_EXECUTED);
        assertThat(logs.get(0).getTargetName()).isEqualTo("createNoticeDraft");
        assertThat(logs.get(0).getDetail()).contains(requested.getPlan().getPlanId());
    }

    // ===== 케이스 6: 승인 거부(취소) → DB 변경 없음 =====

    @Test
    @DisplayName("케이스 6: 계획 취소 시 DB 변경 없음 + 취소된 계획은 승인할 수 없음")
    void case6_approvalDenied_noDbChange() {
        int noticesBefore = noticeCount();
        Long studentsBefore = (long) memberService.getStudents(ACADEMY, null).size();

        AgentDto.ExecuteResponse resp = agentExecutor.execute(ownerCtx,
                "학부모 공지문 작성해줘: 제목: 취소 테스트 내용: 반영되면 안 됨.");
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getRequiresApproval()).isTrue();

        AgentDto.PlanView cancelled = planService.cancel(ownerCtx, resp.getPlan().getPlanId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        org.junit.jupiter.api.Assertions.assertThrows(com.rea.academy.agent.plan.AgentPlanException.class,
                () -> planService.approve(ownerCtx, resp.getPlan().getPlanId(), resp.getPlan().getPlanHash(), "DIRECT"));

        assertThat(noticeCount()).isEqualTo(noticesBefore);
        assertThat((long) memberService.getStudents(ACADEMY, null).size()).isEqualTo(studentsBefore);
        // 취소·생성 흔적은 감사 로그에 남음
        var logs = auditLogRepository
                .findByAcademyIdAndActionTypeOrderByIdDesc(ACADEMY, AuditActionType.AGENT_PLAN_CANCELLED);
        assertThat(logs).isNotEmpty();
    }

    // ===== 케이스 7: 성적 분석 =====

    @Test
    @DisplayName("케이스 7: 성적 변화 분석 → 과목별 추이 표시")
    void case7_gradeAnalysis() {
        AgentDto.ExecuteResponse resp = agentExecutor.execute(ownerCtx,
                "학생 " + kimId + " 이번 달 성적 변화 분석해줘");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getToolUsed()).isEqualTo("analyzeGradeChanges");
        Map<String, Object> result = resp.getResult();
        assertThat((int) result.get("gradeCount")).isEqualTo(3);
        assertThat(result.get("studentName")).isEqualTo("김철수");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subjects = (List<Map<String, Object>>) result.get("subjects");
        Map<String, Object> english = subjects.stream()
                .filter(s -> "영어".equals(s.get("subject"))).findFirst().orElseThrow();
        assertThat(english.get("firstRate")).isEqualTo(70.0);
        assertThat(english.get("lastRate")).isEqualTo(85.0);
        assertThat(english.get("trend")).isEqualTo("상승");
        Map<String, Object> math = subjects.stream()
                .filter(s -> "수학".equals(s.get("subject"))).findFirst().orElseThrow();
        assertThat(math.get("trend")).isEqualTo("유지");
        assertThat(result.get("summary").toString()).contains("상승");
    }

    @Test
    @DisplayName("케이스 7: 성적 데이터 없으면 빈 결과 안내")
    void case7_noGrades_emptyGuidance() {
        AgentDto.ExecuteResponse resp = agentExecutor.execute(ownerCtx,
                "학생 " + leeId + " 이번 달 성적 변화 분석해줘");

        assertThat(resp.isSuccess()).isTrue();
        assertThat((int) resp.getResult().get("gradeCount")).isEqualTo(0);
        assertThat(resp.getResult().get("summary").toString())
                .contains("기간 내 성적 기록이 없습니다");
    }

    @Test
    @DisplayName("케이스 7: 학생 미지정 시 실행 실패 안내 (파싱은 성공)")
    void case7_noStudentId_failsWithGuidance() {
        CommandParser.ParseResult parsed = commandParser.parse("이번 달 성적 변화 분석해줘");
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.toolName()).isEqualTo("analyzeGradeChanges");

        AgentDto.ExecuteResponse resp =
                agentExecutor.execute(ownerCtx, "이번 달 성적 변화 분석해줘");
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).contains("학생 ID");
    }

    // ===== 케이스 8: 파괴적 작업 거부 =====

    @Test
    @DisplayName("케이스 8: '학생 삭제해줘' → 실행 없이 거부, DB 변경 없음")
    void case8_deleteStudent_rejected() {
        int auditsBefore = auditCount();
        Long studentsBefore = (long) memberService.getStudents(ACADEMY, null).size();

        // 승인 플래그가 true여도 파괴적 작업은 파싱 단계에서 거부된다
        AgentDto.ExecuteResponse resp =
                agentExecutor.execute(approved(ownerCtx), "학생 삭제해줘");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getToolUsed()).isNull();
        assertThat(resp.getMessage()).contains("파괴적 작업");
        assertThat((long) memberService.getStudents(ACADEMY, null).size()).isEqualTo(studentsBefore);
        // 파싱 실패는 도구 미실행이므로 감사 로그 미기록 (기존 정책 유지)
        assertThat(auditCount()).isEqualTo(auditsBefore);
    }
}
