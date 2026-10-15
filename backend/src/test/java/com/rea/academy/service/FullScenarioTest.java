package com.rea.academy.service;

import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.AdminDto;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.dto.NotificationDto;
import com.rea.academy.dto.OpsDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * REA 학원관리 최종 통합 시나리오 테스트 (8단계).
 *
 * 전체 흐름을 하나의 학원에서 순서대로 검증한다:
 * 학원 등록 → 원장 등록 → 강사 등록 → 학생 등록 → 반 생성·배정 →
 * 출결 기록 → 알림 발송 → 공지 작성 → 상담(문의·답변) →
 * 숙제 등록·검사 → 성적 등록 → 학습 리포트 → 일정 등록 → 통계 대시보드 →
 * 감사 로그 확인
 *
 * H2 인메모리. 실제 외부 메시지 발송 없음 (MockNotificationSender).
 */
@SpringBootTest
@Transactional
class FullScenarioTest {

    @Autowired
    private AdminService adminService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private ClassService classService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NoticeService noticeService;
    @Autowired
    private ConsultationService consultationService;
    @Autowired
    private LearningService learningService;
    @Autowired
    private OpsService opsService;

    private static final Long ACADEMY = 1L;

    @Test
    @DisplayName("전체 시나리오: 학원 등록부터 통계·감사 로그까지")
    void fullScenario() {
        // 1. 학원 등록
        AdminDto.AcademyInfoResponse academy = adminService.saveAcademyInfo(ACADEMY,
                AdminDto.AcademyInfoUpdateRequest.builder()
                        .name("미래학원")
                        .phone("02-1234-5678")
                        .address("서울시 강남구 학원로 10")
                        .build());
        assertThat(academy.getName()).isEqualTo("미래학원");

        // 2. 원장 등록
        MemberDto.Response owner = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                        .name("김원장").phone("010-0000-0001").build());
        assertThat(owner.getRole()).isEqualTo(ReaRole.ACADEMY_OWNER);

        // 3. 강사 등록
        MemberDto.Response teacher = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.TEACHER)
                        .name("이강사").phone("010-0000-0002").build());
        assertThat(teacher.getRole()).isEqualTo(ReaRole.TEACHER);

        // 4. 학생 등록 (학부모 정보 포함)
        StudentDto.DetailResponse student = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").grade("고2")
                        .parentName("홍부모").parentPhone("010-1111-1111").build());
        assertThat(student.getAuthKey()).isNotBlank();

        // 5. 반 생성 + 학생 배정
        ClassDto.DetailResponse clazz = classService.createClass(ACADEMY,
                ClassDto.CreateRequest.builder()
                        .name("수학A반").teacherId(teacher.getId()).build());
        classService.assignStudents(ACADEMY, clazz.getId(), List.of(student.getId()));
        assertThat(classService.getClassDetail(ACADEMY, clazz.getId())
                .getStudentCount()).isEqualTo(1);

        // 6. 출결 기록 (등원 → 학부모에게 CHECK_IN 알림 자동 발송)
        AttendanceDto.CheckResponse check = attendanceService.check(ACADEMY,
                AttendanceDto.CheckRequest.builder()
                        .studentId(student.getId()).eventType("CHECK_IN").build());
        assertThat(check.getEventType()).isEqualTo("CHECK_IN");
        assertThat(notificationService.countUnread(ACADEMY, student.getId(), null))
                .isGreaterThanOrEqualTo(1);

        // 7. 알림 발송 (원장 → 전체 메시지)
        NotificationDto.SendResponse sent = notificationService.sendMessage(ACADEMY,
                NotificationDto.SendRequest.builder()
                        .targetType("ALL")
                        .title("개강 안내").content("3월 개강 안내입니다.").build());
        assertThat(sent.getSentCount()).isEqualTo(1);
        assertThat(notificationService.getHistory(ACADEMY)).isNotEmpty();

        // 8. 공지 작성 (전체공지 → NOTICE 알림 자동 발송)
        NoticeDto.CreateResponse notice = noticeService.create(ACADEMY,
                NoticeDto.CreateRequest.builder()
                        .title("3월 휴원 안내").content("3월 1일은 휴원입니다.")
                        .authorId(owner.getId()).build());
        assertThat(notice.getNotice().getTitle()).isEqualTo("3월 휴원 안내");
        assertThat(notice.getNotificationSentCount()).isGreaterThanOrEqualTo(1);

        // 9. 상담: 학부모 문의 → 강사 답변 (CONSULT_REPLY 알림)
        ConsultationDto.CreateResponse consultation = consultationService.create(ACADEMY,
                ConsultationDto.CreateRequest.builder()
                        .studentId(student.getId()).type("INQUIRY")
                        .title("수업 진도 문의").content("진도가 어떻게 되나요?").build());
        assertThat(consultation.getConsultation().getStatus()).isEqualTo("OPEN");
        ConsultationDto.CreateMessageResponse reply = consultationService.addMessage(
                ACADEMY, consultation.getConsultation().getId(),
                ConsultationDto.MessageRequest.builder()
                        .content("이번 주 3단원 들어갑니다.").memberId(teacher.getId()).build());
        assertThat(reply.getMessage().getId()).isNotNull();
        assertThat(consultationService.detail(
                ACADEMY, consultation.getConsultation().getId(), null, null)
                .getStatus()).isEqualTo("ANSWERED");

        // 10. 숙제 등록·검사 (HOMEWORK 알림 자동 발송)
        LearningDto.HomeworkCreateResponse homework = learningService.createHomework(ACADEMY,
                LearningDto.HomeworkCreateRequest.builder()
                        .classId(clazz.getId()).title("수학 문제집 p.30")
                        .content("30페이지 풀기")
                        .dueDate(LocalDate.now().plusDays(3).toString()).build());
        assertThat(homework.getNotificationSentCount()).isGreaterThanOrEqualTo(1);
        LearningDto.HomeworkCheckResponse checked = learningService.checkHomework(
                ACADEMY, homework.getHomework().getId(),
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(student.getId()).status("COMPLETED")
                        .score(100).comment("잘했어요").build());
        assertThat(checked.getStatus()).isEqualTo("COMPLETED");

        // 11. 성적 등록 (GRADE 알림 자동 발송)
        LearningDto.GradeCreateResponse grade = learningService.createGrade(ACADEMY,
                LearningDto.GradeCreateRequest.builder()
                        .studentId(student.getId()).examName("3월 모의고사")
                        .subject("수학").score(85).fullScore(100).build());
        assertThat(grade.getGrade().getScore()).isEqualTo(85);

        // 12. 학습 리포트 (성적·숙제·진도·출결 종합)
        learningService.recordProgress(ACADEMY,
                LearningDto.ProgressRecordRequest.builder()
                        .studentId(student.getId()).unit("3단원").progressRate(60).build());
        LearningDto.ReportResponse report =
                learningService.getReport(ACADEMY, student.getId(), null);
        assertThat(report.getStudentName()).isEqualTo("홍학생");
        assertThat(report.getGradeSummary().getGradeCount()).isEqualTo(1);
        assertThat(report.getGradeSummary().getAvgRate()).isEqualTo(85.0);
        assertThat(report.getHomeworkSummary().getAssignedCount()).isEqualTo(1);

        // 13. 일정 등록
        OpsDto.ScheduleResponse schedule = opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder()
                        .title("학부모 상담주간").date(LocalDate.now().toString())
                        .targetType("ALL").memo("상담 예약 받기").build());
        assertThat(schedule.getId()).isNotNull();

        // 14. 통계 대시보드
        OpsDto.DashboardStatsResponse stats = opsService.getDashboardStats(ACADEMY);
        assertThat(stats.getStudentCount()).isEqualTo(1);
        assertThat(stats.getClassCount()).isEqualTo(1);
        assertThat(stats.getTodayAttendance().getCheckInCount()).isEqualTo(1);

        // 15. 감사 로그: 주요 변경 이력이 모두 기록되었는지 확인
        AdminDto.AuditLogListResponse logs = adminService.listAuditLogs(ACADEMY, null);
        assertThat(logs.getLogs()).anyMatch(l -> "STUDENT_CREATED".equals(l.getActionType()));
        assertThat(logs.getLogs()).anyMatch(l -> "CLASS_CREATED".equals(l.getActionType()));
        assertThat(logs.getLogs()).anyMatch(l -> "NOTICE_CREATED".equals(l.getActionType()));
        assertThat(logs.getLogs()).anyMatch(l -> "ACADEMY_INFO_UPDATED".equals(l.getActionType()));

        // 16. 통합 검색에서 학생 횡단 조회
        OpsDto.SearchResponse search = opsService.search(ACADEMY, "홍학생");
        assertThat(search.getStudents()).hasSize(1);
        assertThat(search.getGrades()).isNotEmpty();
        assertThat(search.getConsultations()).isNotEmpty();
    }

    @Test
    @DisplayName("지점 등록 → 지점 학원에서 독립 운영")
    void branchScenario() {
        adminService.saveAcademyInfo(ACADEMY,
                AdminDto.AcademyInfoUpdateRequest.builder().name("본원").build());

        // 지점 등록
        AdminDto.AcademyInfoResponse branch = adminService.createBranch(ACADEMY,
                AdminDto.BranchCreateRequest.builder().name("2호점").build());
        Long branchAcademyId = branch.getId();
        assertThat(branch.getParentAcademyId()).isEqualTo(ACADEMY);

        // 지점에서 독립적으로 원장·학생 등록
        MemberDto.Response branchOwner = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(branchAcademyId).role(ReaRole.ACADEMY_OWNER)
                        .name("박원장").build());
        StudentDto.DetailResponse branchStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(branchAcademyId).name("지점학생").build());

        assertThat(branchOwner.getAcademyId()).isEqualTo(branchAcademyId);
        assertThat(branchStudent.getAcademyId()).isEqualTo(branchAcademyId);

        // 본원에서는 지점 데이터가 보이지 않음
        assertThat(memberService.getStudents(ACADEMY, null)).isEmpty();
        assertThat(adminService.listUsers(branchAcademyId, null).getStaff()).hasSize(1);

        // 지점 목록
        assertThat(adminService.listBranches(ACADEMY)).hasSize(1);
        assertThat(adminService.listBranches(branchAcademyId)).isEmpty();
    }
}
