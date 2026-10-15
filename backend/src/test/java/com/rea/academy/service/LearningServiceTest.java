package com.rea.academy.service;

import com.rea.academy.domain.learning.ReaHomeworkRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.notification.NotificationType;
import com.rea.academy.domain.notification.ReaNotification;
import com.rea.academy.domain.notification.ReaNotificationRepository;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 학습 관리 서비스 테스트 (H2 인메모리).
 * - 숙제 등록(반별/개별)→검사, 진도 기록→조회, 성적 등록→이력
 * - 리포트 집계 (성적·숙제·진도·출결)
 * - HOMEWORK/GRADE 알림 발송 확인 (Mock 발송기)
 * - 학원 격리, 1차(Member)·1단계(Class)·2단계(Attendance) 회귀
 */
@SpringBootTest
@Transactional
class LearningServiceTest {

    @Autowired
    private LearningService learningService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ClassService classService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ReaHomeworkRepository homeworkRepository;

    @Autowired
    private ReaNotificationRepository notificationRepository;

    private static final Long ACADEMY = 1L;
    private static final Long OTHER_ACADEMY = 2L;

    private Long teacherId;
    private Long classId;
    private Long studentId1;
    private Long studentId2;
    private String authKey1;

    @BeforeEach
    void setUp() {
        teacherId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.TEACHER)
                        .name("김강사").phone("010-1234-5678")
                        .build()).getId();

        StudentDto.DetailResponse s1 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").grade("고2").build());
        StudentDto.DetailResponse s2 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("박학생").grade("고1").build());
        studentId1 = s1.getId();
        studentId2 = s2.getId();
        authKey1 = s1.getAuthKey();

        classId = classService.createClass(ACADEMY,
                ClassDto.CreateRequest.builder()
                        .name("수학 A반").teacherId(teacherId).build()).getId();
        classService.assignStudents(ACADEMY, classId, List.of(studentId1, studentId2));
    }

    private LearningDto.HomeworkCreateRequest homeworkReq(
            Long classId, Long studentId, String title) {
        return LearningDto.HomeworkCreateRequest.builder()
                .classId(classId)
                .studentId(studentId)
                .title(title)
                .content("교재 p.30 문제 1~20번")
                .dueDate(LocalDate.now().plusDays(3).toString())
                .build();
    }

    private LearningDto.GradeCreateRequest gradeReq(
            Long studentId, String examName, String subject, int score, int fullScore) {
        return LearningDto.GradeCreateRequest.builder()
                .studentId(studentId)
                .examName(examName)
                .subject(subject)
                .score(score)
                .fullScore(fullScore)
                .examDate(LocalDate.now().toString())
                .build();
    }

    private long countNotifications(NotificationType type) {
        return notificationRepository.findByAcademyIdOrderByCreatedAtDesc(ACADEMY).stream()
                .filter(n -> n.getType() == type)
                .count();
    }

    // ===== 숙제 =====

    @Test
    @DisplayName("반별 숙제 등록 → 소속 학생 학부모에게 HOMEWORK 알림 발송")
    void createClassHomework_sendsNotifications() {
        LearningDto.HomeworkCreateResponse resp =
                learningService.createHomework(ACADEMY, homeworkReq(classId, null, "수학 숙제"));

        assertThat(resp.getHomework().getId()).isNotNull();
        assertThat(resp.getHomework().getClassId()).isEqualTo(classId);
        assertThat(resp.getHomework().getClassName()).isEqualTo("수학 A반");
        assertThat(resp.getHomework().getQuestionRefCount()).isZero();
        assertThat(resp.getNotificationRequestedCount()).isEqualTo(2);
        assertThat(resp.getNotificationSentCount()).isEqualTo(2);
        assertThat(resp.getNotificationError()).isNull();

        // 알림 이력에 HOMEWORK 2건 기록 (Mock 발송, 실제 외부 발송 없음)
        assertThat(countNotifications(NotificationType.HOMEWORK)).isEqualTo(2);
        List<ReaNotification> history =
                notificationRepository.findByAcademyIdOrderByCreatedAtDesc(ACADEMY);
        assertThat(history).allMatch(n -> n.getTitle().startsWith("[숙제]"));
    }

    @Test
    @DisplayName("학생별 숙제 등록 → 해당 학생에게만 알림 발송")
    void createIndividualHomework_notifiesOneStudent() {
        LearningDto.HomeworkCreateResponse resp =
                learningService.createHomework(ACADEMY, homeworkReq(null, studentId1, "개별 숙제"));

        assertThat(resp.getHomework().getStudentId()).isEqualTo(studentId1);
        assertThat(resp.getHomework().getStudentName()).isEqualTo("홍학생");
        assertThat(resp.getNotificationSentCount()).isEqualTo(1);
        assertThat(countNotifications(NotificationType.HOMEWORK)).isEqualTo(1);
    }

    @Test
    @DisplayName("숙제 등록 시 반·학생 미지정 → 400")
    void createHomework_withoutTarget_throws400() {
        assertThatThrownBy(() -> learningService.createHomework(
                ACADEMY, homeworkReq(null, null, "무대상 숙제")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("반 또는 학생");
    }

    @Test
    @DisplayName("숙제 등록 시 내신Wiki 문항 참조 저장 (연동 구조)")
    void createHomework_withQuestionRefs() {
        LearningDto.HomeworkCreateRequest req = homeworkReq(classId, null, "문항 숙제");
        req.setQuestionRefs(List.of(
                LearningDto.QuestionRefRequest.builder()
                        .questionId(1001L).source("NAESIN_WIKI").build(),
                LearningDto.QuestionRefRequest.builder()
                        .questionId(1002L).source(null).build()));

        LearningDto.HomeworkCreateResponse resp = learningService.createHomework(ACADEMY, req);
        assertThat(resp.getHomework().getQuestionRefCount()).isEqualTo(2);

        // DB에 임베디드 컬렉션으로 저장됨
        assertThat(homeworkRepository.findById(resp.getHomework().getId()))
                .isPresent()
                .get()
                .satisfies(h -> {
                    assertThat(h.getQuestionRefs()).hasSize(2);
                    assertThat(h.getQuestionRefs().get(1).getSource()).isEqualTo("NAESIN_WIKI");
                });
    }

    @Test
    @DisplayName("숙제 검사 → 완료 저장 후 학생 목록에서 상태 확인 (HW-02→HW-03)")
    void checkHomework_completed_visibleInStudentList() {
        Long homeworkId = learningService
                .createHomework(ACADEMY, homeworkReq(classId, null, "수학 숙제"))
                .getHomework().getId();

        // 검사 전: 미검사
        List<LearningDto.HomeworkResponse> before =
                learningService.listHomework(ACADEMY, null, studentId1, null);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).getCheckStatus()).isNull();

        // 검사: 완료 + 점수
        LearningDto.HomeworkCheckResponse check = learningService.checkHomework(
                ACADEMY, homeworkId,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(studentId1).status("COMPLETED")
                        .score(95).comment("잘했어요").build());
        assertThat(check.getStatus()).isEqualTo("COMPLETED");
        assertThat(check.getStatusLabel()).isEqualTo("완료");
        assertThat(check.getScore()).isEqualTo(95);
        assertThat(check.getStudentName()).isEqualTo("홍학생");

        // 검사 후: 학생 목록에 상태 반영
        List<LearningDto.HomeworkResponse> after =
                learningService.listHomework(ACADEMY, null, studentId1, null);
        assertThat(after.get(0).getCheckStatus()).isEqualTo("COMPLETED");
        assertThat(after.get(0).getCheckScore()).isEqualTo(95);
        assertThat(after.get(0).getCheckComment()).isEqualTo("잘했어요");

        // 다른 학생은 여전히 미검사
        List<LearningDto.HomeworkResponse> other =
                learningService.listHomework(ACADEMY, null, studentId2, null);
        assertThat(other.get(0).getCheckStatus()).isNull();
    }

    @Test
    @DisplayName("숙제 재검사 → 결과 갱신 (upsert)")
    void checkHomework_recheck_updates() {
        Long homeworkId = learningService
                .createHomework(ACADEMY, homeworkReq(classId, null, "수학 숙제"))
                .getHomework().getId();

        learningService.checkHomework(ACADEMY, homeworkId,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(studentId1).status("INCOMPLETE").build());
        LearningDto.HomeworkCheckResponse recheck = learningService.checkHomework(
                ACADEMY, homeworkId,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(studentId1).status("COMPLETED").score(80).build());

        assertThat(recheck.getStatus()).isEqualTo("COMPLETED");
        assertThat(recheck.getScore()).isEqualTo(80);
        // 같은 (숙제, 학생) 1행 유지
        assertThat(learningService.listHomeworkChecks(ACADEMY, homeworkId)).hasSize(1);
    }

    @Test
    @DisplayName("숙제 검사: 잘못된 상태값 → 400, 음수 점수 → 400")
    void checkHomework_invalidInput_throws400() {
        Long homeworkId = learningService
                .createHomework(ACADEMY, homeworkReq(classId, null, "수학 숙제"))
                .getHomework().getId();

        assertThatThrownBy(() -> learningService.checkHomework(ACADEMY, homeworkId,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(studentId1).status("DONE").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> learningService.checkHomework(ACADEMY, homeworkId,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(studentId1).status("COMPLETED").score(-5).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("학부모 인증키로 자녀 숙제 목록 조회 (HW-03)")
    void listHomework_byAuthKey() {
        learningService.createHomework(ACADEMY, homeworkReq(classId, null, "반 숙제"));
        learningService.createHomework(ACADEMY, homeworkReq(null, studentId1, "개별 숙제"));

        List<LearningDto.HomeworkResponse> list =
                learningService.listHomework(ACADEMY, null, null, authKey1);
        assertThat(list).hasSize(2);
        assertThat(list).extracting(LearningDto.HomeworkResponse::getTitle)
                .containsExactlyInAnyOrder("반 숙제", "개별 숙제");
    }

    // ===== 진도 =====

    @Test
    @DisplayName("진도 기록 → 조회 (최신순)")
    void recordProgress_thenList() {
        learningService.recordProgress(ACADEMY,
                LearningDto.ProgressRecordRequest.builder()
                        .studentId(studentId1).unit("수학Ⅱ 3단원 미분")
                        .progressRate(60).memo("개념 정리 완료")
                        .recordedDate(LocalDate.now().minusDays(2).toString()).build());
        learningService.recordProgress(ACADEMY,
                LearningDto.ProgressRecordRequest.builder()
                        .studentId(studentId1).unit("수학Ⅱ 4단원 적분")
                        .progressRate(20).build());

        List<LearningDto.ProgressResponse> list =
                learningService.getProgress(ACADEMY, studentId1, null);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getUnit()).isEqualTo("수학Ⅱ 4단원 적분");
        assertThat(list.get(0).getProgressRate()).isEqualTo(20);
        assertThat(list.get(1).getMemo()).isEqualTo("개념 정리 완료");
    }

    @Test
    @DisplayName("진도율 범위 위반(101, -1) → 400")
    void recordProgress_invalidRate_throws400() {
        assertThatThrownBy(() -> learningService.recordProgress(ACADEMY,
                LearningDto.ProgressRecordRequest.builder()
                        .studentId(studentId1).unit("단원").progressRate(101).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0~100");
        assertThatThrownBy(() -> learningService.recordProgress(ACADEMY,
                LearningDto.ProgressRecordRequest.builder()
                        .studentId(studentId1).unit("단원").progressRate(-1).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 성적 =====

    @Test
    @DisplayName("성적 등록 → GRADE 알림 발송 → 이력 조회")
    void createGrade_sendsGradeNotification() {
        LearningDto.GradeCreateResponse resp = learningService.createGrade(
                ACADEMY, gradeReq(studentId1, "10월 모의고사", "수학", 85, 100));

        assertThat(resp.getGrade().getId()).isNotNull();
        assertThat(resp.getGrade().getRate()).isEqualTo(85.0);
        assertThat(resp.getGrade().getStudentName()).isEqualTo("홍학생");
        assertThat(resp.getNotificationSentCount()).isEqualTo(1);
        assertThat(resp.getNotificationError()).isNull();

        assertThat(countNotifications(NotificationType.GRADE)).isEqualTo(1);
        List<ReaNotification> history =
                notificationRepository.findByAcademyIdOrderByCreatedAtDesc(ACADEMY);
        assertThat(history.get(0).getTitle()).contains("10월 모의고사");
        // 학부모 알림 목록에서도 확인
        assertThat(notificationService.countUnread(ACADEMY, studentId1, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("성적 검증: 점수>만점·만점<1·음수 점수 → 400")
    void createGrade_invalidScore_throws400() {
        assertThatThrownBy(() -> learningService.createGrade(
                ACADEMY, gradeReq(studentId1, "시험", "수학", 110, 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만점 이하");
        assertThatThrownBy(() -> learningService.createGrade(
                ACADEMY, gradeReq(studentId1, "시험", "수학", 50, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> learningService.createGrade(
                ACADEMY, gradeReq(studentId1, "시험", "수학", -5, 100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("성적 이력 조회 — 시험일자 최신순")
    void getGrades_orderedByExamDateDesc() {
        learningService.createGrade(ACADEMY,
                gradeReq(studentId1, "9월 모의고사", "수학", 70, 100));
        LearningDto.GradeCreateRequest oct = gradeReq(studentId1, "10월 모의고사", "수학", 85, 100);
        oct.setExamDate(LocalDate.now().toString());
        learningService.createGrade(ACADEMY, oct);

        List<LearningDto.GradeResponse> grades =
                learningService.getGrades(ACADEMY, studentId1, null);
        assertThat(grades).hasSize(2);
        assertThat(grades.get(0).getExamName()).isEqualTo("10월 모의고사");
        assertThat(grades.get(1).getExamName()).isEqualTo("9월 모의고사");
    }

    @Test
    @DisplayName("학부모 인증키로 자녀 성적 이력 조회 (GRD-02)")
    void getGrades_byAuthKey() {
        learningService.createGrade(ACADEMY, gradeReq(studentId1, "10월 모의고사", "영어", 90, 100));

        List<LearningDto.GradeResponse> grades =
                learningService.getGrades(ACADEMY, null, authKey1);
        assertThat(grades).hasSize(1);
        assertThat(grades.get(0).getSubject()).isEqualTo("영어");
    }

    // ===== 리포트 =====

    @Test
    @DisplayName("학습 리포트 집계: 성적·숙제·진도·출결")
    void getReport_aggregatesAll() {
        // 성적 3건 (수학 2건, 영어 1건)
        learningService.createGrade(ACADEMY, gradeReq(studentId1, "10월 모의고사", "수학", 80, 100));
        learningService.createGrade(ACADEMY, gradeReq(studentId1, "10월 모의고사", "영어", 90, 100));
        learningService.createGrade(ACADEMY, gradeReq(studentId1, "9월 모의고사", "수학", 70, 100));

        // 숙제 2건 (반별 1 + 개별 1), 그 중 1건 검사 완료
        Long hw1 = learningService.createHomework(ACADEMY, homeworkReq(classId, null, "반 숙제"))
                .getHomework().getId();
        learningService.createHomework(ACADEMY, homeworkReq(null, studentId1, "개별 숙제"));
        learningService.checkHomework(ACADEMY, hw1,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(studentId1).status("COMPLETED").score(90).build());

        // 진도 1건
        learningService.recordProgress(ACADEMY,
                LearningDto.ProgressRecordRequest.builder()
                        .studentId(studentId1).unit("수학Ⅱ 3단원").progressRate(60).build());

        // 출결 2건 (등원 + 지각)
        attendanceService.check(ACADEMY,
                AttendanceDto.CheckRequest.builder()
                        .studentId(studentId1).eventType("CHECK_IN").build());
        attendanceService.check(ACADEMY,
                AttendanceDto.CheckRequest.builder()
                        .studentId(studentId2).eventType("LATE").build());

        LearningDto.ReportResponse report =
                learningService.getReport(ACADEMY, studentId1, null);

        assertThat(report.getStudentName()).isEqualTo("홍학생");

        // 성적 요약: 3건, 평균 (80+90+70)/3=80.0, 수학 평균 75.0, 영어 90.0
        assertThat(report.getGradeSummary().getGradeCount()).isEqualTo(3);
        assertThat(report.getGradeSummary().getAvgRate()).isEqualTo(80.0);
        assertThat(report.getGradeSummary().getSubjectAverages()).hasSize(2);
        assertThat(report.getGradeSummary().getSubjectAverages())
                .filteredOn(s -> s.getSubject().equals("수학"))
                .first().satisfies(s -> assertThat(s.getAvgRate()).isEqualTo(75.0));

        // 숙제 요약: 배정 2, 검사 1, 완료 1, 미검사 1
        assertThat(report.getHomeworkSummary().getAssignedCount()).isEqualTo(2);
        assertThat(report.getHomeworkSummary().getCheckedCount()).isEqualTo(1);
        assertThat(report.getHomeworkSummary().getCompletedCount()).isEqualTo(1);
        assertThat(report.getHomeworkSummary().getIncompleteCount()).isZero();
        assertThat(report.getHomeworkSummary().getUncheckedCount()).isEqualTo(1);

        // 진도 요약: 1건
        assertThat(report.getProgressSummary().getRecordCount()).isEqualTo(1);
        assertThat(report.getProgressSummary().getLatest()).hasSize(1);

        // 출결 요약: 홍학생 등원 1일 (박학생 지각은 제외)
        assertThat(report.getAttendanceSummary().getPeriodDays()).isEqualTo(30);
        assertThat(report.getAttendanceSummary().getCheckInDays()).isEqualTo(1);
        assertThat(report.getAttendanceSummary().getLateDays()).isZero();
        assertThat(report.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("학습 리포트: 데이터 없는 학생도 빈 요약 반환")
    void getReport_emptyStudent() {
        LearningDto.ReportResponse report =
                learningService.getReport(ACADEMY, studentId2, null);

        assertThat(report.getGradeSummary().getGradeCount()).isZero();
        assertThat(report.getGradeSummary().getAvgRate()).isNull();
        assertThat(report.getHomeworkSummary().getAssignedCount()).isZero();
        assertThat(report.getProgressSummary().getRecordCount()).isZero();
        assertThat(report.getAttendanceSummary().getCheckInDays()).isZero();
    }

    // ===== 학원 격리 =====

    @Test
    @DisplayName("학원 격리: 타 학원 학생·반·숙제로 접근 시 400")
    void academyIsolation() {
        Long otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원생").build()).getId();
        Long otherTeacher = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER)
                        .name("타학원강사").build()).getId();
        Long otherClass = classService.createClass(OTHER_ACADEMY,
                ClassDto.CreateRequest.builder()
                        .name("타학원반").teacherId(otherTeacher).build()).getId();

        // 타 학원 학생으로 숙제 등록 → 400
        assertThatThrownBy(() -> learningService.createHomework(
                ACADEMY, homeworkReq(null, otherStudent, "침투 숙제")))
                .isInstanceOf(IllegalArgumentException.class);
        // 타 학원 반으로 숙제 등록 → 400
        assertThatThrownBy(() -> learningService.createHomework(
                ACADEMY, homeworkReq(otherClass, null, "침투 숙제")))
                .isInstanceOf(IllegalArgumentException.class);
        // 타 학원 학생 성적 등록 → 400
        assertThatThrownBy(() -> learningService.createGrade(
                ACADEMY, gradeReq(otherStudent, "시험", "수학", 90, 100)))
                .isInstanceOf(IllegalArgumentException.class);
        // 타 학원 학생 진도 조회 → 400
        assertThatThrownBy(() -> learningService.getProgress(ACADEMY, otherStudent, null))
                .isInstanceOf(IllegalArgumentException.class);
        // 타 학원 학생 리포트 → 400
        assertThatThrownBy(() -> learningService.getReport(ACADEMY, otherStudent, null))
                .isInstanceOf(IllegalArgumentException.class);

        // ACADEMY(1)에 생성된 숙제가 OTHER_ACADEMY(2)에서 조회되지 않음
        Long hwId = learningService.createHomework(ACADEMY, homeworkReq(classId, null, "숙제"))
                .getHomework().getId();
        assertThatThrownBy(() -> learningService.checkHomework(
                OTHER_ACADEMY, hwId,
                LearningDto.HomeworkCheckRequest.builder()
                        .studentId(otherStudent).status("COMPLETED").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(learningService.listHomework(OTHER_ACADEMY, null, null, null)).isEmpty();
    }

    // ===== 회귀 =====

    @Test
    @DisplayName("회귀: 1차 학생 등록·1단계 반 배정·2단계 출결 기록")
    void regression_memberClassAttendance() {
        // 1차: 학생 등록
        StudentDto.DetailResponse s = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("회귀학생").build());
        assertThat(s.getAuthKey()).isNotBlank();

        // 1단계: 반 배정
        classService.assignStudents(ACADEMY, classId, List.of(s.getId()));
        assertThat(classService.getClassDetail(ACADEMY, classId).getStudentCount())
                .isEqualTo(3);

        // 2단계: 출결 기록
        attendanceService.check(ACADEMY,
                AttendanceDto.CheckRequest.builder()
                        .studentId(s.getId()).eventType("CHECK_IN").build());

        // 6단계: 배정된 반의 숙제가 새 학생 목록에 노출
        learningService.createHomework(ACADEMY, homeworkReq(classId, null, "회귀 숙제"));
        assertThat(learningService.listHomework(ACADEMY, null, s.getId(), null)).hasSize(1);
    }
}
