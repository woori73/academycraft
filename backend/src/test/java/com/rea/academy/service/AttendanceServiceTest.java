package com.rea.academy.service;

import com.rea.academy.domain.attendance.ReaAttendanceEventRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 출결 관리 서비스 테스트 (H2 인메모리).
 * - 키패드 등원→하원 자동 판별, 결석 처리, 정정 이력, 학원 격리
 * - 1차(Member)·1단계(Class) 회귀 확인
 */
@SpringBootTest
@Transactional
class AttendanceServiceTest {

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ClassService classService;

    @Autowired
    private ReaAttendanceEventRepository eventRepository;

    private static final Long ACADEMY = 1L;
    private static final Long OTHER_ACADEMY = 2L;

    private Long teacherId;
    private Long classId;
    private Long studentId1;
    private Long studentId2;
    private String authKey1;
    private String authKey2;

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
        authKey2 = s2.getAuthKey();

        classId = classService.createClass(ACADEMY,
                ClassDto.CreateRequest.builder()
                        .name("수학 A반").teacherId(teacherId).build()).getId();
        classService.assignStudents(ACADEMY, classId, List.of(studentId1, studentId2));
    }

    private AttendanceDto.CheckRequest checkReq(Long studentId, String authKey, String type) {
        return AttendanceDto.CheckRequest.builder()
                .studentId(studentId).authKey(authKey).eventType(type).build();
    }

    private AttendanceDto.KeypadRequest keypadReq(String authKey) {
        return AttendanceDto.KeypadRequest.builder().authKey(authKey).build();
    }

    // ===== 키패드 자동 판별 =====

    @Test
    @DisplayName("키패드 첫 입력은 등원(CHECK_IN)으로 자동 판별")
    void keypadFirstInput_recordsCheckIn() {
        AttendanceDto.CheckResponse res = attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1));

        assertThat(res.getEventType()).isEqualTo("CHECK_IN");
        assertThat(res.getAutoDetected()).isTrue();
        assertThat(res.getStudentName()).isEqualTo("홍학생");
        assertThat(res.getSource()).isEqualTo("KEYPAD");
    }

    @Test
    @DisplayName("키패드 두 번째 입력은 하원(CHECK_OUT)으로 자동 판별")
    void keypadSecondInput_recordsCheckOut() {
        attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1));
        AttendanceDto.CheckResponse res = attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1));

        assertThat(res.getEventType()).isEqualTo("CHECK_OUT");
        assertThat(res.getAutoDetected()).isTrue();
    }

    @Test
    @DisplayName("당일 등·하원이 모두 기록된 상태에서 키패드 입력 시 409")
    void keypadThirdInput_throws409() {
        attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1));
        attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1));

        assertThatThrownBy(() -> attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 완료");
    }

    @Test
    @DisplayName("유효하지 않은 출석키는 400")
    void keypadInvalidKey_throws400() {
        assertThatThrownBy(() -> attendanceService.keypadCheck(ACADEMY, keypadReq("WRONG-KEY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("출석키");
    }

    // ===== 직접 기록 =====

    @Test
    @DisplayName("강사가 결석을 직접 기록 (사유 포함)")
    void checkAbsent_manual() {
        AttendanceDto.CheckRequest req = checkReq(studentId1, null, "ABSENT");
        req.setNote("감기");

        AttendanceDto.CheckResponse res = attendanceService.check(ACADEMY, req);

        assertThat(res.getEventType()).isEqualTo("ABSENT");
        assertThat(res.getSource()).isEqualTo("MANUAL");
        assertThat(res.getNote()).isEqualTo("감기");
        assertThat(res.getAutoDetected()).isFalse();
    }

    @Test
    @DisplayName("인증키로도 직접 기록 가능")
    void checkByAuthKey() {
        AttendanceDto.CheckResponse res =
                attendanceService.check(ACADEMY, checkReq(null, authKey2, "LATE"));

        assertThat(res.getStudentId()).isEqualTo(studentId2);
        assertThat(res.getEventType()).isEqualTo("LATE");
    }

    @Test
    @DisplayName("하원 가능 시간 전 하원 기록은 409")
    void checkOutBeforeAllowedTime_throws409() {
        LocalDateTime noon = LocalDate.now().atTime(12, 0);
        attendanceService.updateSettings(ACADEMY, AttendanceDto.SettingsRequest.builder()
                .checkOutEarliestTime("14:00").build());

        // Keep the same-day comparison stable even when CI runs near midnight.
        try (var time = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            time.when(LocalDateTime::now).thenReturn(noon);
            attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1));
            assertThatThrownBy(() -> attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("하원 가능 시간");
        }
    }

    // ===== 반별 출결부 =====

    @Test
    @DisplayName("반별 출결부: 소속 학생 전체의 당일 상태 목록")
    void roster_returnsClassRoster() {
        attendanceService.keypadCheck(ACADEMY, keypadReq(authKey1)); // 등원
        attendanceService.check(ACADEMY, checkReq(studentId2, null, "ABSENT")); // 결석

        AttendanceDto.RosterResponse roster = attendanceService.getRoster(
                ACADEMY, classId, LocalDate.now().toString());

        assertThat(roster.getEntries()).hasSize(2);
        assertThat(roster.getEntries())
                .extracting(AttendanceDto.RosterEntry::getStudentName)
                .containsExactly("박학생", "홍학생"); // 이름 순 정렬
        AttendanceDto.RosterEntry entry1 = roster.getEntries().stream()
                .filter(e -> e.getStudentId().equals(studentId1)).findFirst().orElseThrow();
        assertThat(entry1.getStatus()).isEqualTo("PRESENT");
        assertThat(entry1.getCheckInAt()).isNotNull();
        AttendanceDto.RosterEntry entry2 = roster.getEntries().stream()
                .filter(e -> e.getStudentId().equals(studentId2)).findFirst().orElseThrow();
        assertThat(entry2.getStatus()).isEqualTo("ABSENT");
    }

    // ===== 학생별 이력 =====

    @Test
    @DisplayName("학생별·기간별 출결 이력 조회")
    void studentHistory_dateRange() {
        YearMonth ym = YearMonth.now();
        recordAt(studentId1, "CHECK_IN", ym.atDay(1));
        recordAt(studentId1, "CHECK_OUT", ym.atDay(2));
        recordAt(studentId1, "ABSENT", ym.atDay(3));

        List<AttendanceDto.EventResponse> history = attendanceService.getStudentHistory(
                ACADEMY, studentId1, ym.atDay(1).toString(), ym.atDay(2).toString());

        assertThat(history).hasSize(2);
        assertThat(history).extracting(AttendanceDto.EventResponse::getEventType)
                .containsExactly("CHECK_IN", "CHECK_OUT"); // 발생 일시 순
    }

    // ===== 월별 현황 =====

    @Test
    @DisplayName("월별 현황: 일자별 상태 + 출석·결석·지각 집계")
    void monthly_aggregation() {
        YearMonth ym = YearMonth.now();
        int lastDay = Math.min(5, ym.lengthOfMonth());
        recordAt(studentId1, "CHECK_IN", ym.atDay(1));
        recordAt(studentId1, "ABSENT", ym.atDay(2));
        recordAt(studentId1, "LATE", ym.atDay(3));
        recordAt(studentId1, "CHECK_IN", ym.atDay(4));
        recordAt(studentId1, "EARLY_LEAVE", ym.atDay(lastDay));

        AttendanceDto.MonthlyResponse monthly =
                attendanceService.getMonthly(ACADEMY, classId, ym.toString());

        assertThat(monthly.getDaysInMonth()).isEqualTo(ym.lengthOfMonth());
        AttendanceDto.MonthlyStudent ms = monthly.getStudents().stream()
                .filter(s -> s.getStudentId().equals(studentId1)).findFirst().orElseThrow();
        assertThat(ms.getPresentDays()).isEqualTo(4); // IN×2 + LATE + EARLY_LEAVE
        assertThat(ms.getAbsentDays()).isEqualTo(1);
        assertThat(ms.getLateDays()).isEqualTo(1);
        assertThat(ms.getEarlyLeaveDays()).isEqualTo(1);
        assertThat(ms.getDailyStatus().get(1)).isEqualTo("IN");
        assertThat(ms.getDailyStatus().get(2)).isEqualTo("ABSENT");
        assertThat(ms.getDailyStatus().get(6)).isEqualTo("NONE");
    }

    // ===== 정정 =====

    @Test
    @DisplayName("출결 정정: 기록 수정 + 정정 이력 보존")
    void correctEvent_updatesAndKeepsHistory() {
        AttendanceDto.CheckResponse created =
                attendanceService.check(ACADEMY, checkReq(studentId1, null, "CHECK_IN"));

        AttendanceDto.EventResponse corrected = attendanceService.correctEvent(
                ACADEMY, created.getId(),
                AttendanceDto.CorrectRequest.builder()
                        .eventType("ABSENT").note("착오 입력").reason("키패드 오입력").build());

        assertThat(corrected.getEventType()).isEqualTo("ABSENT");
        assertThat(corrected.getCorrected()).isTrue();
        assertThat(corrected.getCorrections()).hasSize(1);
        AttendanceDto.CorrectionResponse h = corrected.getCorrections().get(0);
        assertThat(h.getBeforeType()).isEqualTo("CHECK_IN");
        assertThat(h.getAfterType()).isEqualTo("ABSENT");
        assertThat(h.getReason()).isEqualTo("키패드 오입력");
    }

    @Test
    @DisplayName("정정 사유 없으면 400")
    void correctWithoutReason_throws400() {
        AttendanceDto.CheckResponse created =
                attendanceService.check(ACADEMY, checkReq(studentId1, null, "CHECK_IN"));

        assertThatThrownBy(() -> attendanceService.correctEvent(
                ACADEMY, created.getId(),
                AttendanceDto.CorrectRequest.builder().eventType("ABSENT").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("정정 사유");
    }

    // ===== 설정 =====

    @Test
    @DisplayName("출결 설정: 미설정 시 기본값, 변경 시 저장")
    void settings_defaultsAndUpdate() {
        AttendanceDto.SettingsResponse defaults = attendanceService.getSettings(ACADEMY);
        assertThat(defaults.getPhotoEnabled()).isFalse();
        assertThat(defaults.getCheckInMessage()).contains("{name}");

        AttendanceDto.SettingsResponse updated = attendanceService.updateSettings(
                ACADEMY, AttendanceDto.SettingsRequest.builder()
                        .checkOutEarliestTime("18:00")
                        .photoEnabled(true)
                        .checkInMessage("{name} 등원 완료")
                        .build());

        assertThat(updated.getCheckOutEarliestTime()).isEqualTo("18:00");
        assertThat(updated.getPhotoEnabled()).isTrue();
        assertThat(updated.getCheckInMessage()).isEqualTo("{name} 등원 완료");
    }

    // ===== 학원 격리 =====

    @Test
    @DisplayName("타 학원 학생 출결 기록 시 400")
    void academyIsolation_otherAcademyStudent() {
        StudentDto.DetailResponse other = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원생").build());

        assertThatThrownBy(() ->
                attendanceService.check(ACADEMY, checkReq(other.getId(), null, "CHECK_IN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("학생을 찾을 수 없습니다");

        assertThatThrownBy(() ->
                attendanceService.keypadCheck(ACADEMY, keypadReq(other.getAuthKey())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("출석키");
    }

    @Test
    @DisplayName("타 학원 반 출결부 조회 시 400")
    void academyIsolation_otherAcademyClass() {
        assertThatThrownBy(() -> attendanceService.getRoster(
                OTHER_ACADEMY, classId, LocalDate.now().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("반을 찾을 수 없습니다");
    }

    // ===== 1차·1단계 회귀 =====

    @Test
    @DisplayName("회귀: 1차 학생 등록·1단계 반 생성·배정이 그대로 동작")
    void regression_memberAndClass() {
        StudentDto.DetailResponse s = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("회귀학생").build());
        assertThat(s.getAuthKey()).isNotBlank();

        ClassDto.DetailResponse c = classService.createClass(ACADEMY,
                ClassDto.CreateRequest.builder()
                        .name("회귀반").teacherId(teacherId).capacity(2).build());
        classService.assignStudents(ACADEMY, c.getId(), List.of(s.getId()));

        assertThat(classService.getClassDetail(ACADEMY, c.getId()).getStudentCount()).isEqualTo(1);
        // 출결도 연동 확인
        AttendanceDto.CheckResponse res =
                attendanceService.check(ACADEMY, checkReq(s.getId(), null, "CHECK_IN"));
        assertThat(res.getEventType()).isEqualTo("CHECK_IN");
        assertThat(eventRepository.count()).isPositive();
    }

    // ===== 헬퍼 =====

    private void recordAt(Long studentId, String type, LocalDate date) {
        AttendanceDto.CheckRequest req = checkReq(studentId, null, type);
        req.setOccurredAt(date.atTime(9, 0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        attendanceService.check(ACADEMY, req);
    }
}
