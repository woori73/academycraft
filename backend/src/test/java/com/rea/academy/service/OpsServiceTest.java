package com.rea.academy.service;

import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.dto.OpsDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 운영 관리 서비스 테스트 (7단계, H2 인메모리).
 * - 일정 CRUD (월별 조회·수정·삭제) + 상담 일정 연결
 * - 강사 담당 현황 (담당 반 + 업무 메모 upsert)
 * - 통합 검색 (학생 이름 횡단: 학생·출결·성적·숙제·상담·공지)
 * - 통계 대시보드 집계
 * - CSV 내보내기·가져오기 (엑셀 호환: UTF-8 BOM)
 * - 학원 격리, 1~6단계 회귀
 */
@SpringBootTest
@Transactional
class OpsServiceTest {

    @Autowired
    private OpsService opsService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ClassService classService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private ConsultationService consultationService;

    @Autowired
    private NoticeService noticeService;

    @Autowired
    private LearningService learningService;

    private static final Long ACADEMY = 1L;
    private static final Long OTHER_ACADEMY = 2L;

    private Long teacherId;
    private Long otherTeacherId;
    private Long classId;
    private Long studentId1;
    private Long studentId2;

    @BeforeEach
    void setUp() {
        teacherId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.TEACHER)
                        .name("김강사").phone("010-1234-5678")
                        .build()).getId();
        otherTeacherId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER)
                        .name("이강사")
                        .build()).getId();

        StudentDto.DetailResponse s1 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").grade("고2")
                        .parentName("홍부모").parentPhone("010-1111-1111").build());
        StudentDto.DetailResponse s2 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("박학생").grade("고1").build());
        studentId1 = s1.getId();
        studentId2 = s2.getId();

        classId = classService.createClass(ACADEMY,
                ClassDto.CreateRequest.builder()
                        .name("수학A반").teacherId(teacherId).build()).getId();
        classService.assignStudents(ACADEMY, classId, List.of(studentId1, studentId2));
    }

    private String thisMonth() {
        return YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private String today() {
        return LocalDate.now().toString();
    }

    // ===== 일정 CRUD =====

    @Test
    @DisplayName("일정 등록 → 월별 조회에 포함")
    void createAndListSchedule() {
        OpsDto.ScheduleResponse created = opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder()
                        .title("휴원일").date(today())
                        .startTime("09:00").endTime("18:00")
                        .memo("창립기념일 휴원").build());

        assertThat(created.getId()).isNotNull();
        assertThat(created.getTargetType()).isEqualTo("ALL");
        assertThat(created.getTargetTypeLabel()).isEqualTo("전체");

        List<OpsDto.ScheduleResponse> list = opsService.listSchedules(ACADEMY, thisMonth());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getTitle()).isEqualTo("휴원일");
        assertThat(list.get(0).getStartTime()).isEqualTo("09:00");
    }

    @Test
    @DisplayName("반별 일정 등록 (CLASS + classId)")
    void createClassSchedule() {
        OpsDto.ScheduleResponse created = opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder()
                        .title("수학A반 특강").date(today())
                        .targetType("CLASS").classId(classId).build());

        assertThat(created.getTargetType()).isEqualTo("CLASS");
        assertThat(created.getClassId()).isEqualTo(classId);
        assertThat(created.getClassName()).isEqualTo("수학A반");
    }

    @Test
    @DisplayName("일정 등록 검증: 제목·일자·시간 범위")
    void createScheduleValidation() {
        // 제목 없음
        assertThatThrownBy(() -> opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder().date(today()).build()))
                .isInstanceOf(IllegalArgumentException.class);
        // 일자 형식 오류
        assertThatThrownBy(() -> opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder().title("t").date("2026/10/13").build()))
                .isInstanceOf(IllegalArgumentException.class);
        // 종료 시각이 시작 시각보다 빠름
        assertThatThrownBy(() -> opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder().title("t").date(today())
                        .startTime("18:00").endTime("09:00").build()))
                .isInstanceOf(IllegalArgumentException.class);
        // 반별 일정인데 반 미지정
        assertThatThrownBy(() -> opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder().title("t").date(today())
                        .targetType("CLASS").build()))
                .isInstanceOf(IllegalArgumentException.class);
        // 잘못된 targetType
        assertThatThrownBy(() -> opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder().title("t").date(today())
                        .targetType("WRONG").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("일정 수정: 지정 항목만 변경")
    void updateSchedule() {
        OpsDto.ScheduleResponse created = opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder()
                        .title("원래 제목").date(today()).memo("원래 메모").build());

        OpsDto.ScheduleResponse updated = opsService.updateSchedule(ACADEMY, created.getId(),
                OpsDto.ScheduleUpdateRequest.builder()
                        .title("바뀐 제목").memo("바뀐 메모").build());

        assertThat(updated.getTitle()).isEqualTo("바뀐 제목");
        assertThat(updated.getMemo()).isEqualTo("바뀐 메모");
        assertThat(updated.getDate()).isEqualTo(LocalDate.now()); // 변경 없음
    }

    @Test
    @DisplayName("일정 수정: CLASS → ALL 전환 시 반 연결 초기화")
    void updateScheduleTargetToAll() {
        OpsDto.ScheduleResponse created = opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder()
                        .title("반 일정").date(today())
                        .targetType("CLASS").classId(classId).build());

        OpsDto.ScheduleResponse updated = opsService.updateSchedule(ACADEMY, created.getId(),
                OpsDto.ScheduleUpdateRequest.builder().targetType("ALL").build());

        assertThat(updated.getTargetType()).isEqualTo("ALL");
        assertThat(updated.getClassId()).isNull();
        assertThat(updated.getClassName()).isNull();
    }

    @Test
    @DisplayName("일정 삭제 + 타 학원 일정 접근 400")
    void deleteSchedule() {
        OpsDto.ScheduleResponse created = opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder().title("삭제할 일정").date(today()).build());

        opsService.deleteSchedule(ACADEMY, created.getId());
        assertThat(opsService.listSchedules(ACADEMY, thisMonth())).isEmpty();

        OpsDto.ScheduleResponse other = opsService.createSchedule(OTHER_ACADEMY,
                OpsDto.ScheduleCreateRequest.builder().title("타 학원 일정").date(today()).build());
        assertThatThrownBy(() -> opsService.deleteSchedule(ACADEMY, other.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> opsService.updateSchedule(ACADEMY, other.getId(),
                OpsDto.ScheduleUpdateRequest.builder().title("x").build()))
                .isInstanceOf(IllegalArgumentException.class);
        // 타 학원 일정은 목록에 안 보임
        assertThat(opsService.listSchedules(ACADEMY, thisMonth())).isEmpty();
    }

    @Test
    @DisplayName("월별 조회: 다른 달 일정은 제외")
    void listSchedulesFiltersMonth() {
        opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder().title("이번 달").date(today()).build());
        opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder()
                        .title("다음 달")
                        .date(YearMonth.now().plusMonths(1).atDay(5).toString()).build());

        List<OpsDto.ScheduleResponse> list = opsService.listSchedules(ACADEMY, thisMonth());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getTitle()).isEqualTo("이번 달");
    }

    // ===== 상담 일정 연결 =====

    @Test
    @DisplayName("상담 일정: 상담 스레드 연결 등록")
    void createScheduleLinkedToConsultation() {
        ConsultationDto.CreateResponse consultation = consultationService.create(ACADEMY,
                ConsultationDto.CreateRequest.builder()
                        .studentId(studentId1).type("INQUIRY")
                        .title("수학 진도 상담").content("상담 내용").build());

        OpsDto.ScheduleResponse schedule = opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder()
                        .title("홍학생 학부모 대면 상담")
                        .date(today()).startTime("15:00").endTime("15:30")
                        .consultationId(consultation.getConsultation().getId())
                        .memo("2층 상담실").build());

        assertThat(schedule.getConsultationId())
                .isEqualTo(consultation.getConsultation().getId());
        assertThat(schedule.getConsultationTitle()).isEqualTo("수학 진도 상담");
    }

    @Test
    @DisplayName("상담 일정: 타 학원 상담 연결 시 400")
    void linkOtherAcademyConsultationFails() {
        StudentDto.DetailResponse otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원생").build());
        ConsultationDto.CreateResponse otherConsultation = consultationService.create(OTHER_ACADEMY,
                ConsultationDto.CreateRequest.builder()
                        .studentId(otherStudent.getId()).type("INQUIRY")
                        .title("타 학원 상담").content("내용").build());

        assertThatThrownBy(() -> opsService.createSchedule(ACADEMY,
                OpsDto.ScheduleCreateRequest.builder()
                        .title("잘못된 연결").date(today())
                        .consultationId(otherConsultation.getConsultation().getId()).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 강사 담당 =====

    @Test
    @DisplayName("강사 담당 현황: 담당 반 + 학생 수 + 메모 미등록")
    void getTeacherAssignments() {
        OpsDto.TeacherAssignmentResponse res =
                opsService.getTeacherAssignments(ACADEMY, teacherId);

        assertThat(res.getTeacherName()).isEqualTo("김강사");
        assertThat(res.getClassCount()).isEqualTo(1);
        assertThat(res.getClasses().get(0).getClassName()).isEqualTo("수학A반");
        assertThat(res.getClasses().get(0).getStudentCount()).isEqualTo(2);
        assertThat(res.getTotalStudentCount()).isEqualTo(2);
        assertThat(res.getDutyMemo()).isNull();
    }

    @Test
    @DisplayName("담당 업무 메모 upsert: 등록 → 수정 → 삭제")
    void upsertDutyMemo() {
        OpsDto.TeacherAssignmentResponse afterCreate = opsService.updateDutyMemo(
                ACADEMY, teacherId, "월수금 초등부 관리, 상담 요일: 화·목");
        assertThat(afterCreate.getDutyMemo()).contains("초등부 관리");

        OpsDto.TeacherAssignmentResponse afterUpdate = opsService.updateDutyMemo(
                ACADEMY, teacherId, "화목 중등부 관리");
        assertThat(afterUpdate.getDutyMemo()).isEqualTo("화목 중등부 관리");
        // 행은 1개 유지, 담당 반은 그대로
        assertThat(afterUpdate.getClassCount()).isEqualTo(1);

        OpsDto.TeacherAssignmentResponse afterClear = opsService.updateDutyMemo(
                ACADEMY, teacherId, null);
        assertThat(afterClear.getDutyMemo()).isNull();
    }

    @Test
    @DisplayName("강사 담당: 타 학원 강사 조회 시 400")
    void teacherAssignmentIsolation() {
        assertThatThrownBy(() -> opsService.getTeacherAssignments(ACADEMY, otherTeacherId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> opsService.updateDutyMemo(ACADEMY, otherTeacherId, "메모"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 통합 검색 =====

    @Test
    @DisplayName("통합 검색: 학생 이름으로 출결·성적·숙제·상담 횡단 검색")
    void unifiedSearch() {
        // 출결 기록
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId1).eventType("CHECK_IN").build());
        // 성적 등록
        learningService.createGrade(ACADEMY, LearningDto.GradeCreateRequest.builder()
                .studentId(studentId1).examName("10월 모의고사").subject("수학")
                .score(90).fullScore(100).build());
        // 숙제 등록 (개별)
        learningService.createHomework(ACADEMY, LearningDto.HomeworkCreateRequest.builder()
                .studentId(studentId1).title("수학 문제집 p.30").content("풀기")
                .dueDate(LocalDate.now().plusDays(3).toString()).build());
        // 상담 스레드
        consultationService.create(ACADEMY, ConsultationDto.CreateRequest.builder()
                .studentId(studentId1).type("INQUIRY")
                .title("홍학생 진도 문의").content("문의 내용").build());

        OpsDto.SearchResponse res = opsService.search(ACADEMY, "홍학생");

        assertThat(res.getStudents()).hasSize(1);
        assertThat(res.getStudents().get(0).getStudentName()).isEqualTo("홍학생");
        assertThat(res.getAttendances()).hasSize(1);
        assertThat(res.getAttendances().get(0).getEventType()).isEqualTo("CHECK_IN");
        assertThat(res.getAttendances().get(0).getEventTypeLabel()).isEqualTo("등원");
        assertThat(res.getGrades()).hasSize(1);
        assertThat(res.getGrades().get(0).getExamName()).isEqualTo("10월 모의고사");
        assertThat(res.getHomework()).hasSize(1);
        assertThat(res.getHomework().get(0).getTitle()).isEqualTo("수학 문제집 p.30");
        assertThat(res.getConsultations()).hasSize(1);
        assertThat(res.getConsultations().get(0).getTitle()).isEqualTo("홍학생 진도 문의");
    }

    @Test
    @DisplayName("통합 검색: 공지 제목 일치 + 빈 검색어 400")
    void searchNoticesAndEmptyQuery() {
        noticeService.create(ACADEMY, NoticeDto.CreateRequest.builder()
                .title("홍학생 학부모 설명회 안내").content("내용").build());

        OpsDto.SearchResponse res = opsService.search(ACADEMY, "설명회");
        assertThat(res.getNotices()).hasSize(1);
        assertThat(res.getNotices().get(0).getScope()).isEqualTo("전체공지");

        assertThatThrownBy(() -> opsService.search(ACADEMY, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("통합 검색: 타 학원 데이터 제외")
    void searchIsolation() {
        memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(OTHER_ACADEMY).name("홍타학원생").build());

        OpsDto.SearchResponse res = opsService.search(ACADEMY, "홍");
        assertThat(res.getStudents()).hasSize(1);
        assertThat(res.getStudents().get(0).getStudentName()).isEqualTo("홍학생");
    }

    // ===== 통계 대시보드 =====

    @Test
    @DisplayName("대시보드: 학생 수·반 수 집계")
    void dashboardCounts() {
        OpsDto.DashboardStatsResponse stats = opsService.getDashboardStats(ACADEMY);

        assertThat(stats.getStudentCount()).isEqualTo(2);
        assertThat(stats.getClassCount()).isEqualTo(1);
        assertThat(stats.getMonth()).isEqualTo(thisMonth());
        assertThat(stats.getTodayAttendance().getDate()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("대시보드: 오늘 출결 현황 집계")
    void dashboardTodayAttendance() {
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId1).eventType("CHECK_IN").build());
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId2).eventType("ABSENT").build());

        OpsDto.TodayAttendanceStats today =
                opsService.getDashboardStats(ACADEMY).getTodayAttendance();

        assertThat(today.getCheckInCount()).isEqualTo(1);
        assertThat(today.getAbsentCount()).isEqualTo(1);
        assertThat(today.getCheckOutCount()).isZero();
        assertThat(today.getStudentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("대시보드: 미답변 상담 수·이번 달 공지 수")
    void dashboardConsultationAndNotice() {
        consultationService.create(ACADEMY, ConsultationDto.CreateRequest.builder()
                .studentId(studentId1).type("INQUIRY")
                .title("미답변 문의").content("내용").build());
        noticeService.create(ACADEMY, NoticeDto.CreateRequest.builder()
                .title("10월 공지").content("내용").build());

        OpsDto.DashboardStatsResponse stats = opsService.getDashboardStats(ACADEMY);

        assertThat(stats.getUnansweredConsultationCount()).isEqualTo(1);
        assertThat(stats.getMonthlyNoticeCount()).isEqualTo(1);
    }

    // ===== CSV 내보내기·가져오기 =====

    @Test
    @DisplayName("학생 CSV 내보내기: BOM + 헤더 + 행")
    void exportStudentsCsv() {
        byte[] bytes = opsService.exportStudentsCsv(ACADEMY);
        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF"); // UTF-8 BOM (엑셀 한글 호환)
        assertThat(csv).contains("이름,학년,학교,인증키,학부모명,학부모연락처,반,등록일");
        assertThat(csv).contains("홍학생");
        assertThat(csv).contains("박학생");
        assertThat(csv).contains("수학A반");
        assertThat(csv).contains("홍부모");
    }

    @Test
    @DisplayName("학생 CSV 가져오기: 헤더+2행 → 등록+반 배정")
    void importStudentsCsv() {
        String csv = "이름,학년,반,학부모명,연락처\n"
                + "김신입,중3,수학A반,김부모,010-2222-2222\n"
                + "이신입,중2,수학A반,이부모,010-3333-3333\n";

        OpsDto.StudentImportResponse res = opsService.importStudentsCsv(ACADEMY, csv);

        assertThat(res.getTotalRows()).isEqualTo(2);
        assertThat(res.getSuccessCount()).isEqualTo(2);
        assertThat(res.getFailedCount()).isZero();
        assertThat(res.getErrors()).isEmpty();

        // 반 배정 확인: 수학A반 학생 수 2 → 4
        OpsDto.TeacherAssignmentResponse assignments =
                opsService.getTeacherAssignments(ACADEMY, teacherId);
        assertThat(assignments.getClasses().get(0).getStudentCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("학생 CSV 가져오기: 이름 빈 행 실패 + 없는 반 경고")
    void importStudentsCsvErrors() {
        String csv = "최신입,고1,없는반,최부모,010-4444-4444\n"
                + ",고1,수학A반,무명부모,010-5555-5555\n";

        OpsDto.StudentImportResponse res = opsService.importStudentsCsv(ACADEMY, csv);

        assertThat(res.getTotalRows()).isEqualTo(2);
        assertThat(res.getSuccessCount()).isEqualTo(1); // 최신입은 등록됨
        assertThat(res.getFailedCount()).isEqualTo(1);  // 이름 빈 행
        assertThat(res.getErrors()).hasSize(2);
        assertThat(res.getErrors().get(0)).contains("없는반");
        assertThat(res.getErrors().get(1)).contains("이름이 비어 있습니다");
    }

    @Test
    @DisplayName("학생 CSV 가져오기: 헤더 없이도 동작 + BOM 허용")
    void importStudentsCsvNoHeader() {
        String csv = "﻿박신입,초6,,박부모,010-6666-6666\n";

        OpsDto.StudentImportResponse res = opsService.importStudentsCsv(ACADEMY, csv);

        assertThat(res.getTotalRows()).isEqualTo(1);
        assertThat(res.getSuccessCount()).isEqualTo(1);
        assertThat(opsService.search(ACADEMY, "박신입").getStudents()).hasSize(1);
    }

    @Test
    @DisplayName("출결 CSV 내보내기: 반별·월별")
    void exportAttendanceCsv() {
        String occurredAt = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId1).eventType("CHECK_IN").occurredAt(occurredAt).build());
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId2).eventType("LATE").occurredAt(occurredAt).build());

        byte[] bytes = opsService.exportAttendanceCsv(ACADEMY, classId, thisMonth());
        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("일자,이름,반,구분,발생시각,메모");
        assertThat(csv).contains("홍학생");
        assertThat(csv).contains("수학A반");
        assertThat(csv).contains("등원");
        assertThat(csv).contains("지각");
    }

    @Test
    @DisplayName("출결 CSV 내보내기: 학원 전체 + 잘못된 yearMonth 400")
    void exportAttendanceCsvAllAndValidation() {
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId1).eventType("CHECK_IN").build());

        byte[] bytes = opsService.exportAttendanceCsv(ACADEMY, null, thisMonth());
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).contains("홍학생");

        assertThatThrownBy(() -> opsService.exportAttendanceCsv(ACADEMY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> opsService.exportAttendanceCsv(ACADEMY, null, "2026-13"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 학원 격리 =====

    @Test
    @DisplayName("학원 격리: 빈 학원의 대시보드·검색·내보내기")
    void academyIsolation() {
        OpsDto.DashboardStatsResponse stats = opsService.getDashboardStats(OTHER_ACADEMY);
        assertThat(stats.getStudentCount()).isZero();
        assertThat(stats.getClassCount()).isZero();
        assertThat(stats.getUnansweredConsultationCount()).isZero();

        assertThat(opsService.listSchedules(OTHER_ACADEMY, thisMonth())).isEmpty();
        assertThat(opsService.search(OTHER_ACADEMY, "홍학생").getStudents()).isEmpty();

        String csv = new String(opsService.exportStudentsCsv(OTHER_ACADEMY),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("이름,학년,학교,인증키,학부모명,학부모연락처,반,등록일");
        assertThat(csv).doesNotContain("홍학생");
    }

    // ===== 1~6단계 회귀 =====

    @Test
    @DisplayName("회귀: 1단계 학생 등록·반 배정, 2단계 출결, 5단계 상담")
    void regressionEarlierPhases() {
        // 1단계: 학생 등록
        StudentDto.DetailResponse s = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("회귀학생").build());
        assertThat(s.getId()).isNotNull();

        // 1단계: 반 배정
        classService.assignStudents(ACADEMY, classId, List.of(s.getId()));
        assertThat(opsService.getTeacherAssignments(ACADEMY, teacherId)
                .getClasses().get(0).getStudentCount()).isEqualTo(3);

        // 2단계: 출결 기록
        AttendanceDto.CheckResponse check = attendanceService.check(ACADEMY,
                AttendanceDto.CheckRequest.builder()
                        .studentId(s.getId()).eventType("CHECK_IN").build());
        assertThat(check.getEventType()).isEqualTo("CHECK_IN");

        // 5단계: 상담 스레드 생성
        ConsultationDto.CreateResponse c = consultationService.create(ACADEMY,
                ConsultationDto.CreateRequest.builder()
                        .studentId(s.getId()).type("INQUIRY")
                        .title("회귀 문의").content("내용").build());
        assertThat(c.getConsultation().getId()).isNotNull();

        // 7단계 검색에서 회귀 데이터가 횡단 조회됨
        OpsDto.SearchResponse res = opsService.search(ACADEMY, "회귀학생");
        assertThat(res.getStudents()).hasSize(1);
        assertThat(res.getAttendances()).hasSize(1);
        assertThat(res.getConsultations()).hasSize(1);
    }
}
