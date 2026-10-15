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
 * 학원 관리·관리자 기능 테스트 (8단계, H2 인메모리).
 * - 학원 기본정보 조회·수정 (upsert)
 * - 지점 관리 (본원·지점 관계)
 * - 사용자·권한 관리 (역할별 목록·권한 변경·학부모 연결)
 * - 변경 이력(감사 로그): 기존 서비스(AOP) + 관리 기능 기록, 종류별 필터
 * - 학원별 데이터 격리 (전 도메인)
 */
@SpringBootTest
@Transactional
class AdminServiceTest {

    @Autowired
    private AdminService adminService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ClassService classService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private NoticeService noticeService;

    @Autowired
    private ConsultationService consultationService;

    @Autowired
    private LearningService learningService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private OpsService opsService;

    private static final Long ACADEMY = 1L;
    private static final Long OTHER_ACADEMY = 2L;

    private Long ownerId;
    private Long teacherId;

    @BeforeEach
    void setUp() {
        ownerId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                        .name("김원장").phone("010-0000-0001")
                        .build()).getId();
        teacherId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.TEACHER)
                        .name("이강사")
                        .build()).getId();
    }

    private AdminDto.AcademyInfoUpdateRequest infoReq(String name) {
        return AdminDto.AcademyInfoUpdateRequest.builder()
                .name(name)
                .phone("02-1234-5678")
                .address("서울시 강남구 테스트로 1")
                .build();
    }

    // ================= 학원 기본정보 =================

    @Test
    @DisplayName("학원 등록(PUT upsert) → 조회")
    void registerAndGetAcademyInfo() {
        AdminDto.AcademyInfoResponse created =
                adminService.saveAcademyInfo(ACADEMY, infoReq("테스트학원"));

        assertThat(created.getId()).isEqualTo(ACADEMY);
        assertThat(created.getName()).isEqualTo("테스트학원");
        assertThat(created.getPhone()).isEqualTo("02-1234-5678");
        assertThat(created.isBranch()).isFalse();
        assertThat(created.getParentAcademyId()).isNull();

        AdminDto.AcademyInfoResponse fetched = adminService.getAcademyInfo(ACADEMY);
        assertThat(fetched.getName()).isEqualTo("테스트학원");
        assertThat(fetched.getAddress()).isEqualTo("서울시 강남구 테스트로 1");
    }

    @Test
    @DisplayName("미등록 학원 조회 → 400")
    void getUnregisteredAcademyInfoFails() {
        assertThatThrownBy(() -> adminService.getAcademyInfo(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("학원 등록 시 이름 없으면 400")
    void registerWithoutNameFails() {
        assertThatThrownBy(() -> adminService.saveAcademyInfo(ACADEMY,
                        AdminDto.AcademyInfoUpdateRequest.builder().phone("02-1111").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("학원 정보 수정: 제공된 항목만 변경, null은 유지")
    void updateAcademyInfoPartial() {
        adminService.saveAcademyInfo(ACADEMY, infoReq("테스트학원"));

        AdminDto.AcademyInfoResponse updated = adminService.saveAcademyInfo(ACADEMY,
                AdminDto.AcademyInfoUpdateRequest.builder()
                        .phone("02-9999-8888")
                        .memo("메모 추가")
                        .build());

        assertThat(updated.getName()).isEqualTo("테스트학원"); // 유지
        assertThat(updated.getPhone()).isEqualTo("02-9999-8888"); // 변경
        assertThat(updated.getAddress()).isEqualTo("서울시 강남구 테스트로 1"); // 유지
        assertThat(updated.getMemo()).isEqualTo("메모 추가");
    }

    @Test
    @DisplayName("학원 정보 수정 시 빈 이름 → 400")
    void updateAcademyInfoBlankNameFails() {
        adminService.saveAcademyInfo(ACADEMY, infoReq("테스트학원"));
        assertThatThrownBy(() -> adminService.saveAcademyInfo(ACADEMY,
                        AdminDto.AcademyInfoUpdateRequest.builder().name("  ").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("학원 등록·수정 시 감사 로그 기록")
    void academyInfoAuditLogged() {
        adminService.saveAcademyInfo(ACADEMY, infoReq("테스트학원"));
        adminService.saveAcademyInfo(ACADEMY,
                AdminDto.AcademyInfoUpdateRequest.builder().phone("02-0000").build());

        AdminDto.AuditLogListResponse logs =
                adminService.listAuditLogs(ACADEMY, "ACADEMY_INFO_UPDATED");
        assertThat(logs.getTotalCount()).isEqualTo(2);
        assertThat(logs.getLogs()).allMatch(l ->
                "학원 정보 수정".equals(l.getActionTypeLabel())
                        && "테스트학원".equals(l.getTargetName()));
    }

    // ================= 지점 관리 =================

    @Test
    @DisplayName("지점 등록 → 본원 관계·새 학원 ID 발급")
    void createBranch() {
        adminService.saveAcademyInfo(ACADEMY, infoReq("본원학원"));

        AdminDto.AcademyInfoResponse branch = adminService.createBranch(ACADEMY,
                AdminDto.BranchCreateRequest.builder()
                        .name("2호점")
                        .phone("02-2222-2222")
                        .build());

        assertThat(branch.isBranch()).isTrue();
        assertThat(branch.getParentAcademyId()).isEqualTo(ACADEMY);
        assertThat(branch.getName()).isEqualTo("2호점");
        assertThat(branch.getId()).isNotEqualTo(ACADEMY);

        // 본원 정보는 그대로
        assertThat(adminService.getAcademyInfo(ACADEMY).isBranch()).isFalse();

        List<AdminDto.AcademyInfoResponse> branches = adminService.listBranches(ACADEMY);
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0).getName()).isEqualTo("2호점");
    }

    @Test
    @DisplayName("본원 미등록 상태에서 지점 등록 → 400")
    void createBranchWithoutParentFails() {
        assertThatThrownBy(() -> adminService.createBranch(ACADEMY,
                        AdminDto.BranchCreateRequest.builder().name("2호점").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("지점 이름 없으면 400")
    void createBranchWithoutNameFails() {
        adminService.saveAcademyInfo(ACADEMY, infoReq("본원학원"));
        assertThatThrownBy(() -> adminService.createBranch(ACADEMY,
                        AdminDto.BranchCreateRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("지점 등록 시 본원·지점 양쪽에 감사 로그 기록")
    void branchAuditLoggedOnBoth() {
        adminService.saveAcademyInfo(ACADEMY, infoReq("본원학원"));
        AdminDto.AcademyInfoResponse branch = adminService.createBranch(ACADEMY,
                AdminDto.BranchCreateRequest.builder().name("2호점").build());

        assertThat(adminService.listAuditLogs(ACADEMY, "BRANCH_CREATED").getTotalCount())
                .isEqualTo(1);
        assertThat(adminService.listAuditLogs(branch.getId(), "BRANCH_CREATED").getTotalCount())
                .isEqualTo(1);
    }

    // ================= 사용자·권한 관리 =================

    @Test
    @DisplayName("역할 미지정 → 원장·강사 전체 목록")
    void listUsersAll() {
        AdminDto.UserListResponse users = adminService.listUsers(ACADEMY, null);
        assertThat(users.getStaff()).hasSize(2);
        assertThat(users.getParents()).isEmpty();
    }

    @Test
    @DisplayName("role=TEACHER → 강사만, role=OWNER → 원장만")
    void listUsersByRole() {
        AdminDto.UserListResponse teachers = adminService.listUsers(ACADEMY, "TEACHER");
        assertThat(teachers.getStaff()).hasSize(1);
        assertThat(teachers.getStaff().get(0).getRole()).isEqualTo(ReaRole.TEACHER);
        assertThat(teachers.getStaff().get(0).getRoleLabel()).isEqualTo("강사");

        AdminDto.UserListResponse owners = adminService.listUsers(ACADEMY, "owner");
        assertThat(owners.getStaff()).hasSize(1);
        assertThat(owners.getStaff().get(0).getRoleLabel()).isEqualTo("원장");
    }

    @Test
    @DisplayName("role=PARENT → 학부모 연결 목록 (연결 여부 포함)")
    void listParentLinks() {
        memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name("홍학생")
                .parentName("홍부모").parentPhone("010-1111-1111").build());
        memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name("박학생").build());

        AdminDto.UserListResponse users = adminService.listUsers(ACADEMY, "PARENT");
        assertThat(users.getStaff()).isEmpty();
        assertThat(users.getParents()).hasSize(2);

        AdminDto.ParentLinkResponse connected = users.getParents().stream()
                .filter(p -> "홍학생".equals(p.getStudentName())).findFirst().orElseThrow();
        assertThat(connected.isConnected()).isTrue();
        assertThat(connected.getParentName()).isEqualTo("홍부모");
        assertThat(connected.getParentPhone()).isEqualTo("010-1111-1111");

        AdminDto.ParentLinkResponse notConnected = users.getParents().stream()
                .filter(p -> "박학생".equals(p.getStudentName())).findFirst().orElseThrow();
        assertThat(notConnected.isConnected()).isFalse();
    }

    @Test
    @DisplayName("알 수 없는 role → 400")
    void listUsersInvalidRoleFails() {
        assertThatThrownBy(() -> adminService.listUsers(ACADEMY, "ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("권한 변경: 원장이 아닌 요청자 → 400")
    void changeRoleByNonOwnerFails() {
        assertThatThrownBy(() -> adminService.changeMemberRole(ACADEMY, teacherId,
                        AdminDto.RoleChangeRequest.builder()
                                .role("OWNER").actorMemberId(teacherId).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("원장만");
    }

    @Test
    @DisplayName("권한 변경: 요청자 누락 → 400")
    void changeRoleWithoutActorFails() {
        assertThatThrownBy(() -> adminService.changeMemberRole(ACADEMY, teacherId,
                        AdminDto.RoleChangeRequest.builder().role("OWNER").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("권한 변경: 원장이 강사를 원장으로 승격 (원장 교체 1단계)")
    void promoteTeacherToOwner() {
        AdminDto.StaffResponse promoted = adminService.changeMemberRole(
                ACADEMY, teacherId,
                AdminDto.RoleChangeRequest.builder()
                        .role("OWNER").actorMemberId(ownerId).build());

        assertThat(promoted.getRole()).isEqualTo(ReaRole.ACADEMY_OWNER);
        assertThat(promoted.getRoleLabel()).isEqualTo("원장");
        // 인증키·이름 등 기존 정보 유지 (복사+merge 방식 검증)
        assertThat(promoted.getName()).isEqualTo("이강사");

        // 감사 로그 기록 확인
        AdminDto.AuditLogListResponse logs =
                adminService.listAuditLogs(ACADEMY, "MEMBER_ROLE_CHANGED");
        assertThat(logs.getTotalCount()).isEqualTo(1);
        assertThat(logs.getLogs().get(0).getDetail()).contains("강사 → 원장");
        assertThat(logs.getLogs().get(0).getActorName()).isEqualTo("김원장");
    }

    @Test
    @DisplayName("권한 변경: 원장 교체(인수인계) 2단계 — 후임 승격 → 전임 강등")
    void transferOwnership() {
        // 1단계: 후임 승격
        adminService.changeMemberRole(ACADEMY, teacherId,
                AdminDto.RoleChangeRequest.builder()
                        .role("OWNER").actorMemberId(ownerId).build());
        // 2단계: 전임 강등 (원장이 2명이므로 마지막 원장 규칙에 걸리지 않음)
        AdminDto.StaffResponse demoted = adminService.changeMemberRole(
                ACADEMY, ownerId,
                AdminDto.RoleChangeRequest.builder()
                        .role("TEACHER").actorMemberId(teacherId).build());

        assertThat(demoted.getRole()).isEqualTo(ReaRole.TEACHER);

        // 최종: 원장 1명(이강사) + 강사 1명(김원장)
        List<AdminDto.StaffResponse> owners = adminService.listUsers(ACADEMY, "OWNER").getStaff();
        assertThat(owners).hasSize(1);
        assertThat(owners.get(0).getName()).isEqualTo("이강사");

        assertThat(adminService.listAuditLogs(ACADEMY, "MEMBER_ROLE_CHANGED")
                .getTotalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("권한 변경: 같은 역할로 변경 → 400")
    void changeRoleToSameRoleFails() {
        assertThatThrownBy(() -> adminService.changeMemberRole(ACADEMY, teacherId,
                        AdminDto.RoleChangeRequest.builder()
                                .role("TEACHER").actorMemberId(ownerId).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("권한 변경: 학부모 역할로 변경 → 400")
    void changeRoleToParentFails() {
        assertThatThrownBy(() -> adminService.changeMemberRole(ACADEMY, teacherId,
                        AdminDto.RoleChangeRequest.builder()
                                .role("PARENT").actorMemberId(ownerId).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("권한 변경: 마지막 원장 강등 → 409")
    void demoteLastOwnerFails() {
        assertThatThrownBy(() -> adminService.changeMemberRole(ACADEMY, ownerId,
                        AdminDto.RoleChangeRequest.builder()
                                .role("TEACHER").actorMemberId(ownerId).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("마지막 원장");
    }

    @Test
    @DisplayName("권한 변경: 타 학원 요청자 → 400")
    void changeRoleByOtherAcademyActorFails() {
        Long otherOwner = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).role(ReaRole.ACADEMY_OWNER)
                        .name("타원장")
                        .build()).getId();

        assertThatThrownBy(() -> adminService.changeMemberRole(ACADEMY, teacherId,
                        AdminDto.RoleChangeRequest.builder()
                                .role("OWNER").actorMemberId(otherOwner).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("권한 변경: 원장 없는 학원에서는 승격 불가 (요청자 검증) + 감사 로그 없음")
    void promoteInOwnerlessAcademyFails() {
        Long t1 = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER)
                        .name("차강사1")
                        .build()).getId();
        Long t2 = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER)
                        .name("차강사2")
                        .build()).getId();

        // 요청자가 원장이 아니므로 400. 첫 원장은 회원 등록(createMember)으로 만든다.
        assertThatThrownBy(() -> adminService.changeMemberRole(OTHER_ACADEMY, t1,
                        AdminDto.RoleChangeRequest.builder()
                                .role("OWNER").actorMemberId(t2).build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("원장만");

        assertThat(adminService.listAuditLogs(OTHER_ACADEMY, "MEMBER_ROLE_CHANGED")
                .getTotalCount()).isZero();
    }

    // ================= 변경 이력 (AOP — 기존 서비스) =================

    @Test
    @DisplayName("학생 등록·삭제 시 감사 로그 기록 (AOP)")
    void studentAuditLogged() {
        StudentDto.DetailResponse s = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").grade("고2").build());
        memberService.deactivateStudent(ACADEMY, s.getId());

        AdminDto.AuditLogListResponse logs = adminService.listAuditLogs(ACADEMY, null);
        assertThat(logs.getLogs())
                .anyMatch(l -> "STUDENT_CREATED".equals(l.getActionType())
                        && "홍학생".equals(l.getTargetName()));
        assertThat(logs.getLogs())
                .anyMatch(l -> "STUDENT_DELETED".equals(l.getActionType())
                        && "홍학생".equals(l.getTargetName()));
    }

    @Test
    @DisplayName("반 생성·종료 시 감사 로그 기록 (AOP)")
    void classAuditLogged() {
        ClassDto.DetailResponse c = classService.createClass(ACADEMY,
                ClassDto.CreateRequest.builder()
                        .name("수학A반").teacherId(teacherId).build());
        classService.closeClass(ACADEMY, c.getId());

        AdminDto.AuditLogListResponse logs = adminService.listAuditLogs(ACADEMY, null);
        assertThat(logs.getLogs())
                .anyMatch(l -> "CLASS_CREATED".equals(l.getActionType())
                        && "수학A반".equals(l.getTargetName()));
        assertThat(logs.getLogs())
                .anyMatch(l -> "CLASS_CLOSED".equals(l.getActionType())
                        && "수학A반".equals(l.getTargetName()));
    }

    @Test
    @DisplayName("출결 정정 시 감사 로그 기록 (AOP)")
    void attendanceCorrectionAuditLogged() {
        StudentDto.DetailResponse s = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").build());
        AttendanceDto.CheckResponse check = attendanceService.check(ACADEMY,
                AttendanceDto.CheckRequest.builder()
                        .studentId(s.getId()).eventType("CHECK_IN").build());
        attendanceService.correctEvent(ACADEMY, check.getId(),
                AttendanceDto.CorrectRequest.builder()
                        .eventType("LATE").reason("지각 정정").build());

        AdminDto.AuditLogListResponse logs =
                adminService.listAuditLogs(ACADEMY, "ATTENDANCE_CORRECTED");
        assertThat(logs.getTotalCount()).isEqualTo(1);
        assertThat(logs.getLogs().get(0).getTargetName()).isEqualTo("홍학생");
    }

    @Test
    @DisplayName("공지 작성·삭제 시 감사 로그 기록 (AOP)")
    void noticeAuditLogged() {
        NoticeDto.CreateResponse created = noticeService.create(ACADEMY,
                NoticeDto.CreateRequest.builder()
                        .title("휴원 안내").content("내일 휴원입니다.").build());
        noticeService.delete(ACADEMY, created.getNotice().getId());

        AdminDto.AuditLogListResponse logs = adminService.listAuditLogs(ACADEMY, null);
        assertThat(logs.getLogs())
                .anyMatch(l -> "NOTICE_CREATED".equals(l.getActionType())
                        && "휴원 안내".equals(l.getTargetName()));
        assertThat(logs.getLogs())
                .anyMatch(l -> "NOTICE_DELETED".equals(l.getActionType())
                        && "휴원 안내".equals(l.getTargetName()));
    }

    @Test
    @DisplayName("이력 종류 필터: 대소문자 무시")
    void auditLogActionTypeFilterCaseInsensitive() {
        memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name("홍학생").build());

        assertThat(adminService.listAuditLogs(ACADEMY, "student_created").getTotalCount())
                .isEqualTo(1);
        assertThatThrownBy(() -> adminService.listAuditLogs(ACADEMY, "UNKNOWN_TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("감사 로그 최신순 정렬")
    void auditLogsOrderedByNewest() {
        memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name("첫째").build());
        memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name("둘째").build());

        List<AdminDto.AuditLogResponse> logs =
                adminService.listAuditLogs(ACADEMY, "STUDENT_CREATED").getLogs();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getTargetName()).isEqualTo("둘째");
        assertThat(logs.get(1).getTargetName()).isEqualTo("첫째");
    }

    // ================= 학원별 데이터 격리 (전 도메인) =================

    @Test
    @DisplayName("격리: 타 학원 감사 로그 조회 불가")
    void auditLogIsolation() {
        memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(OTHER_ACADEMY).name("타학원학생").build());

        AdminDto.AuditLogListResponse logs = adminService.listAuditLogs(ACADEMY, null);
        assertThat(logs.getLogs()).noneMatch(l -> "타학원학생".equals(l.getTargetName()));
    }

    @Test
    @DisplayName("격리: 타 학원 학원 정보·지점 접근 불가")
    void academyInfoIsolation() {
        adminService.saveAcademyInfo(OTHER_ACADEMY, infoReq("타학원"));
        adminService.createBranch(OTHER_ACADEMY,
                AdminDto.BranchCreateRequest.builder().name("타지점").build());

        assertThat(adminService.listBranches(ACADEMY)).isEmpty();
        assertThat(adminService.listBranches(OTHER_ACADEMY)).hasSize(1);
    }

    @Test
    @DisplayName("격리: 타 학원 회원·학생 접근 불가 (회원 도메인)")
    void memberIsolation() {
        memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER).name("타강사").build());
        memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(OTHER_ACADEMY).name("타학생").build());

        assertThat(adminService.listUsers(ACADEMY, null).getStaff()).hasSize(2);
        assertThat(adminService.listUsers(ACADEMY, "PARENT").getParents()).isEmpty();
        assertThatThrownBy(() -> memberService.getStudentDetail(ACADEMY, 999999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("격리: 타 학원 반·출결 접근 불가 (반·출결 도메인)")
    void classAttendanceIsolation() {
        Long otherTeacher = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER).name("타강사").build()).getId();
        Long otherClass = classService.createClass(OTHER_ACADEMY,
                ClassDto.CreateRequest.builder().name("타반").teacherId(otherTeacher).build()).getId();
        StudentDto.DetailResponse otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder().academyId(OTHER_ACADEMY).name("타학생").build());
        classService.assignStudents(OTHER_ACADEMY, otherClass, List.of(otherStudent.getId()));
        attendanceService.check(OTHER_ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(otherStudent.getId()).eventType("CHECK_IN").build());

        assertThatThrownBy(() -> classService.getClassDetail(ACADEMY, otherClass))
                .isInstanceOf(IllegalArgumentException.class);
        // 타 학원 학생의 출결 이력 조회 → 400 (엄격 차단)
        assertThatThrownBy(() -> attendanceService.getStudentHistory(
                ACADEMY, otherStudent.getId(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(opsService.getDashboardStats(ACADEMY).getStudentCount()).isZero();
    }

    @Test
    @DisplayName("격리: 타 학원 공지·상담 접근 불가 (공지·상담 도메인)")
    void noticeConsultationIsolation() {
        NoticeDto.CreateResponse notice = noticeService.create(OTHER_ACADEMY,
                NoticeDto.CreateRequest.builder().title("타공지").content("내용").build());
        StudentDto.DetailResponse otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder().academyId(OTHER_ACADEMY).name("타학생").build());
        ConsultationDto.CreateResponse consultation = consultationService.create(OTHER_ACADEMY,
                ConsultationDto.CreateRequest.builder()
                        .studentId(otherStudent.getId()).type("INQUIRY")
                        .title("타문의").content("내용").build());

        assertThatThrownBy(() -> noticeService.detail(ACADEMY, notice.getNotice().getId(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consultationService.detail(
                ACADEMY, consultation.getConsultation().getId(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(noticeService.list(ACADEMY, null, null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("격리: 타 학원 학습 데이터 접근 불가 (학습 도메인)")
    void learningIsolation() {
        StudentDto.DetailResponse otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder().academyId(OTHER_ACADEMY).name("타학생").build());
        learningService.createGrade(OTHER_ACADEMY, LearningDto.GradeCreateRequest.builder()
                .studentId(otherStudent.getId()).examName("중간고사")
                .subject("수학").score(90).fullScore(100).build());
        learningService.createHomework(OTHER_ACADEMY, LearningDto.HomeworkCreateRequest.builder()
                .studentId(otherStudent.getId()).title("타숙제").content("내용")
                .dueDate(LocalDate.now().plusDays(1).toString()).build());

        // 타 학원 학생의 성적·숙제 조회 → 400 (엄격 차단)
        assertThatThrownBy(() -> learningService.getGrades(ACADEMY, otherStudent.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> learningService.listHomework(
                ACADEMY, null, otherStudent.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("격리: 타 학원 알림·일정 접근 불가 (알림·운영 도메인)")
    void notificationOpsIsolation() {
        StudentDto.DetailResponse otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder().academyId(OTHER_ACADEMY).name("타학생").build());
        notificationService.sendMessage(OTHER_ACADEMY, NotificationDto.SendRequest.builder()
                .targetType("INDIVIDUAL").targetIds(List.of(otherStudent.getId()))
                .title("타알림").content("내용").build());
        opsService.createSchedule(OTHER_ACADEMY, OpsDto.ScheduleCreateRequest.builder()
                .title("타일정").date(LocalDate.now().toString()).build());

        assertThat(notificationService.getHistory(ACADEMY)).isEmpty();
        String ym = LocalDate.now().toString().substring(0, 7);
        assertThat(opsService.listSchedules(ACADEMY, ym)).isEmpty();
    }

    // ================= 끝 =================
}
