package com.rea.academy.service;

import com.rea.academy.domain.clazz.ClassEnrollmentRepository;
import com.rea.academy.domain.attendance.ReaAttendanceEventRepository;
import com.rea.academy.domain.learning.ReaGradeRepository;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REA 6단계 작업 2 (2-2) — 데이터 정합성 검증 (서비스 레벨).
 *
 * <p>수납·결제는 REA 개발 범위에서 의도적으로 제외(결제선생 외부 방침)되어 검증 대상이 아니다.
 * 대신 학생 등록 → 반 배정 → 출결 기록 → 성적 입력 → 리포트 생성 흐름의 연결 무결성과,
 * 학생 비활성(소프트 삭제)·반 폐강(반 닫기) 시 참조 무결성을 검증한다.
 * H2 인메모리, 가명 더미 데이터.</p>
 */
@SpringBootTest
@Transactional
class DataIntegrityTest {

    private static final Long ACADEMY = 1L;

    @Autowired
    private MemberService memberService;
    @Autowired
    private ClassService classService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private LearningService learningService;
    @Autowired
    private ConsultationService consultationService;
    @Autowired
    private ReaMemberRepository memberRepository;
    @Autowired
    private ReaAttendanceEventRepository eventRepository;
    @Autowired
    private ReaGradeRepository gradeRepository;
    @Autowired
    private ClassEnrollmentRepository enrollmentRepository;

    private long seedOwner() {
        long ownerId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                .name("원장").phone("010-0000-0001").build()).getId();
        return ownerId;
    }

    private long seedStudent(String name) {
        return memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name(name).grade("고2").build()).getId();
    }

    // ===== 2-2a: 전체 흐름 연결 무결성 =====

    @Test
    @DisplayName("학생 등록 → 반 배정 → 출결 → 성적 → 리포트: 데이터 연결 무결성")
    void fullFlow_linkageIntegrity() {
        long ownerId = seedOwner();
        long studentId = seedStudent("정합성학생");
        long classId = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name("정합성반").teacherId(ownerId).build()).getId();

        // 반 배정 → 출결 기록 → 숙제 등록 → 성적 입력
        classService.assignStudents(ACADEMY, classId, List.of(studentId));
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId).eventType("CHECK_IN").build());
        learningService.createHomework(ACADEMY, LearningDto.HomeworkCreateRequest.builder()
                .classId(classId).title("정합성숙제").content("숙제 내용")
                .dueDate("2026-10-20").build());
        learningService.createGrade(ACADEMY, LearningDto.GradeCreateRequest.builder()
                .studentId(studentId).examName("중간고사").subject("수학")
                .score(90).fullScore(100).examDate("2026-10-01").build());

        // 리포트에 전부 반영되는지 확인
        LearningDto.ReportResponse report = learningService.getReport(ACADEMY, studentId, null);
        assertThat(report.getStudentId()).isEqualTo(studentId);
        assertThat(report.getStudentName()).isEqualTo("정합성학생");
        assertThat(report.getGradeSummary().getGradeCount()).isEqualTo(1L);
        assertThat(report.getGradeSummary().getAvgRate()).isEqualTo(90.0);
        assertThat(report.getGradeSummary().getLatestExamName()).isEqualTo("중간고사");
        assertThat(report.getHomeworkSummary().getAssignedCount()).isEqualTo(1L);
        assertThat(report.getAttendanceSummary().getCheckInDays()).isEqualTo(1L);

        // 성적 조회와 출결 이력도 동일한 학생 ID로 연결됨
        assertThat(learningService.getGrades(ACADEMY, studentId, null)).hasSize(1);
        assertThat(attendanceService.getStudentHistory(ACADEMY, studentId, null, null)).isNotEmpty();
    }

    // ===== 2-2b: 학생 비활성(소프트 삭제) 후 참조 무결성 =====

    @Test
    @DisplayName("학생 비활성 후 참조 무결성: DB에는 기록 보존, 서비스 조회는 정책에 따라 분리")
    void studentDeactivate_preservesRelatedData() {
        long studentId = seedStudent("비활성학생");

        // 기록 생성
        attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId).eventType("CHECK_IN").build());
        learningService.createGrade(ACADEMY, LearningDto.GradeCreateRequest.builder()
                .studentId(studentId).examName("중간고사").subject("영어")
                .score(85).fullScore(100).examDate("2026-10-01").build());
        long consultationId = consultationService.create(ACADEMY,
                ConsultationDto.CreateRequest.builder()
                        .studentId(studentId).type("INQUIRY")
                        .title("문의").content("내용").build())
                .getConsultation().getId();

        // 비활성 (DELETE API = 소프트 삭제)
        memberService.deactivateStudent(ACADEMY, studentId);

        // 목록에서 제외되고, 상세 조회는 "찾을 수 없음"으로 명시적 처리
        assertThat(memberService.getStudents(ACADEMY, "비활성학생")).isEmpty();
        assertThatThrownBy(() -> memberService.getStudentDetail(ACADEMY, studentId))
                .isInstanceOf(IllegalArgumentException.class);

        // DB 레벨: 관련 기록은 삭제되지 않고 그대로 보존 (캐스케이드 삭제 없음)
        assertThat(eventRepository
                .findByAcademyIdAndStudentIdOrderByOccurredAtDesc(ACADEMY, studentId))
                .isNotEmpty();
        assertThat(gradeRepository
                .findByAcademyIdAndStudentIdOrderByExamDateDescIdDesc(ACADEMY, studentId))
                .hasSize(1);

        // 서비스 정책: 학생 기준 이력 조회는 비활성 학생에 대해 차단
        // (requireStudent가 활성 학생만 허용 — "학생을 찾을 수 없습니다.")
        assertThatThrownBy(() -> attendanceService.getStudentHistory(ACADEMY, studentId, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> learningService.getGrades(ACADEMY, studentId, null))
                .isInstanceOf(IllegalArgumentException.class);

        // 상담 스레드는 스레드 ID 기준 조회이므로 비활성 학생이어도 상세 조회 가능
        // (requireConsultation은 스레드 active만 확인)
        assertThat(consultationService.detail(ACADEMY, consultationId, null, null).getId())
                .isEqualTo(consultationId);
    }

    // ===== 2-2c: 반 닫기 후 배정 학생 처리 =====

    @Test
    @DisplayName("반 닫기: 배정 학생은 활성 유지, 반 소속(배정)만 해제됨")
    void classClose_deactivatesEnrollments_keepsStudentsActive() {
        long ownerId = seedOwner();
        long studentId = seedStudent("폐강반학생");
        long classId = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name("폐강반").teacherId(ownerId).build()).getId();
        classService.assignStudents(ACADEMY, classId, List.of(studentId));

        // 배정 확인
        assertThat(enrollmentRepository
                .findByClassIdAndStudentIdAndActiveTrue(classId, studentId)).isPresent();

        // 반 닫기
        classService.closeClass(ACADEMY, classId);

        // 배정은 비활성화되지만 학생 자체는 활성 유지
        assertThat(enrollmentRepository
                .findByClassIdAndStudentIdAndActiveTrue(classId, studentId)).isEmpty();
        assertThat(memberService.getStudentDetail(ACADEMY, studentId).getActive()).isTrue();

        // 다른 반에 재배정 가능
        long classId2 = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name("새반").teacherId(ownerId).build()).getId();
        classService.assignStudents(ACADEMY, classId2, List.of(studentId));
        assertThat(enrollmentRepository
                .findByClassIdAndStudentIdAndActiveTrue(classId2, studentId)).isPresent();
    }

    @Test
    @DisplayName("비활성 학생에게는 반 배정 불가 (배정 시 검증)")
    void assignDeactivatedStudent_fails() {
        long ownerId = seedOwner();
        long studentId = seedStudent("배정불가학생");
        memberService.deactivateStudent(ACADEMY, studentId);
        long classId = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name("배정반").teacherId(ownerId).build()).getId();

        assertThatThrownBy(() -> classService.assignStudents(ACADEMY, classId, List.of(studentId)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
