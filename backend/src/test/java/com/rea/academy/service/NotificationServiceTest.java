package com.rea.academy.service;

import com.rea.academy.domain.attendance.ReaAttendanceEventRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.notification.MockNotificationSender;
import com.rea.academy.domain.notification.NotificationType;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.NotificationDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 알림 관리 서비스 테스트 (H2 인메모리).
 * - 메시지 발송 (개별/반별/전체) → 이력 기록
 * - Mock 발송 (실제 외부 발송 없음)
 * - 실패 → 재시도 (상태·재시도횟수)
 * - 알림 설정 ON/OFF
 * - 출결 연동 (등원/하원 알림 자동 발송)
 * - 학원 격리
 */
@SpringBootTest
@Transactional
class NotificationServiceTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ClassService classService;

    @Autowired
    private ReaAttendanceEventRepository attendanceEventRepository;

    @Autowired
    private MockNotificationSender mockSender;

    private static final Long ACADEMY = 1L;
    private static final Long OTHER_ACADEMY = 2L;

    private Long classId;
    private Long studentId1;
    private Long studentId2;
    private String authKey1;

    @BeforeEach
    void setUp() {
        mockSender.setForceFail(false);

        Long teacherId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.TEACHER)
                        .name("김강사").phone("010-1234-5678")
                        .build()).getId();

        StudentDto.DetailResponse s1 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").grade("고2")
                        .parentName("홍부모").parentPhone("010-1111-2222")
                        .build());
        StudentDto.DetailResponse s2 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("박학생").grade("고1")
                        .build());
        studentId1 = s1.getId();
        studentId2 = s2.getId();
        authKey1 = s1.getAuthKey();

        classId = classService.createClass(ACADEMY,
                ClassDto.CreateRequest.builder()
                        .name("수학 A반").teacherId(teacherId).build()).getId();
        classService.assignStudents(ACADEMY, classId, List.of(studentId1, studentId2));
    }

    private NotificationDto.SendRequest sendReq(String targetType, List<Long> targetIds) {
        return NotificationDto.SendRequest.builder()
                .targetType(targetType)
                .targetIds(targetIds)
                .title("테스트 공지")
                .content("테스트 내용입니다.")
                .build();
    }

    // ===== 메시지 발송 =====

    @Test
    @DisplayName("개별 발송: 지정 학생에게만 알림 기록, 상태 SENT")
    void sendMessageIndividual_recordsHistory() {
        NotificationDto.SendResponse res =
                notificationService.sendMessage(ACADEMY, sendReq("INDIVIDUAL", List.of(studentId1)));

        assertThat(res.getRequestedCount()).isEqualTo(1);
        assertThat(res.getSentCount()).isEqualTo(1);
        assertThat(res.getFailedCount()).isZero();
        assertThat(res.getNotificationIds()).hasSize(1);

        List<NotificationDto.HistoryResponse> history = notificationService.getHistory(ACADEMY);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getStatus()).isEqualTo("SENT");
        assertThat(history.get(0).getChannel()).isEqualTo("PUSH");
        assertThat(history.get(0).getType()).isEqualTo("NOTICE");
        assertThat(history.get(0).getTitle()).isEqualTo("테스트 공지");
        assertThat(history.get(0).getRecipientName()).contains("홍학생");
    }

    @Test
    @DisplayName("반별 발송: 해당 반 소속 학생에게만 발송")
    void sendMessageClass_targetsEnrolledStudents() {
        // student2를 반에서 제외 → student1에게만 발송되어야 함
        classService.removeStudent(ACADEMY, classId, studentId2);

        NotificationDto.SendResponse res =
                notificationService.sendMessage(ACADEMY, sendReq("CLASS", List.of(classId)));

        assertThat(res.getRequestedCount()).isEqualTo(1);
        assertThat(res.getSentCount()).isEqualTo(1);

        List<NotificationDto.NotificationResponse> list =
                notificationService.getNotifications(ACADEMY, studentId2, null);
        assertThat(list).isEmpty();
    }

    @Test
    @DisplayName("전체 발송: 학원 전체 활성 학생에게 발송")
    void sendMessageAll_targetsAllActiveStudents() {
        NotificationDto.SendResponse res =
                notificationService.sendMessage(ACADEMY, sendReq("ALL", null));

        assertThat(res.getRequestedCount()).isEqualTo(2);
        assertThat(res.getSentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("타 학원 학생을 개별 발송 대상으로 지정하면 400")
    void sendMessageOtherAcademyStudent_throws400() {
        StudentDto.DetailResponse other = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원생").build());

        assertThatThrownBy(() -> notificationService.sendMessage(
                ACADEMY, sendReq("INDIVIDUAL", List.of(other.getId()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("타 학원 반을 반별 발송 대상으로 지정하면 400")
    void sendMessageOtherAcademyClass_throws400() {
        Long otherTeacher = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER)
                        .name("타강사").phone("010-9999-9999").build()).getId();
        Long otherClass = classService.createClass(OTHER_ACADEMY,
                ClassDto.CreateRequest.builder().name("타반").teacherId(otherTeacher).build()).getId();

        assertThatThrownBy(() -> notificationService.sendMessage(
                ACADEMY, sendReq("CLASS", List.of(otherClass))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== Mock 발송 · 실패 · 재시도 =====

    @Test
    @DisplayName("Mock 강제 실패: FAILED 기록 + 실패 사유 저장, 재시도 후 SENT·재시도횟수 1")
    void forceFailThenRetry_marksFailedThenSent() {
        mockSender.setForceFail(true);
        NotificationDto.SendResponse res =
                notificationService.sendMessage(ACADEMY, sendReq("INDIVIDUAL", List.of(studentId1)));
        assertThat(res.getFailedCount()).isEqualTo(1);
        assertThat(res.getSentCount()).isZero();

        Long notificationId = res.getNotificationIds().get(0);
        NotificationDto.HistoryResponse failed =
                notificationService.getHistory(ACADEMY).stream()
                        .filter(h -> h.getId().equals(notificationId))
                        .findFirst().orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getFailReason()).isNotBlank();

        mockSender.setForceFail(false);
        NotificationDto.HistoryResponse retried =
                notificationService.retry(ACADEMY, notificationId);
        assertThat(retried.getStatus()).isEqualTo("SENT");
        assertThat(retried.getRetryCount()).isEqualTo(1);
        assertThat(retried.getFailReason()).isNull();
    }

    @Test
    @DisplayName("성공한 알림을 재시도하면 409")
    void retrySentNotification_throws409() {
        NotificationDto.SendResponse res =
                notificationService.sendMessage(ACADEMY, sendReq("INDIVIDUAL", List.of(studentId1)));

        assertThatThrownBy(() -> notificationService.retry(ACADEMY, res.getNotificationIds().get(0)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("타 학원 알림을 재시도하면 400")
    void retryOtherAcademyNotification_throws400() {
        NotificationDto.SendResponse res =
                notificationService.sendMessage(ACADEMY, sendReq("INDIVIDUAL", List.of(studentId1)));

        assertThatThrownBy(() -> notificationService.retry(OTHER_ACADEMY, res.getNotificationIds().get(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 알림 설정 ON/OFF =====

    @Test
    @DisplayName("수신 거부(OFF) 설정된 종류는 발송 생략 (skipped)")
    void settingOff_skipsSend() {
        notificationService.updateSettings(ACADEMY, NotificationDto.SettingsRequest.builder()
                .studentId(studentId1)
                .settings(List.of(NotificationDto.SettingUpdate.builder()
                        .type("NOTICE").enabled(false).build()))
                .build());

        NotificationDto.SendResponse res =
                notificationService.sendMessage(ACADEMY, sendReq("ALL", null));

        assertThat(res.getRequestedCount()).isEqualTo(2);
        assertThat(res.getSentCount()).isEqualTo(1);
        assertThat(res.getSkippedCount()).isEqualTo(1);
        assertThat(notificationService.getNotifications(ACADEMY, studentId1, null)).isEmpty();
    }

    @Test
    @DisplayName("알림 설정 조회: 미설정 종류는 기본 ON, 변경 후 반영")
    void settings_getAndUpdate() {
        NotificationDto.SettingsResponse before =
                notificationService.getSettings(ACADEMY, studentId1, null);
        assertThat(before.getSettings()).hasSize(NotificationType.values().length);
        assertThat(before.getSettings()).allMatch(e -> e.isEnabled());

        notificationService.updateSettings(ACADEMY, NotificationDto.SettingsRequest.builder()
                .authKey(authKey1)
                .settings(List.of(
                        NotificationDto.SettingUpdate.builder().type("grade").enabled(false).build(),
                        NotificationDto.SettingUpdate.builder().type("HOMEWORK").enabled(false).build()))
                .build());

        NotificationDto.SettingsResponse after =
                notificationService.getSettings(ACADEMY, null, authKey1);
        assertThat(after.getSettings().stream()
                .filter(e -> e.getType().equals("GRADE")).findFirst().orElseThrow().isEnabled())
                .isFalse();
        assertThat(after.getSettings().stream()
                .filter(e -> e.getType().equals("HOMEWORK")).findFirst().orElseThrow().isEnabled())
                .isFalse();
        assertThat(after.getSettings().stream()
                .filter(e -> e.getType().equals("NOTICE")).findFirst().orElseThrow().isEnabled())
                .isTrue();
    }

    // ===== 알림 목록 · 읽음 처리 =====

    @Test
    @DisplayName("알림 목록 조회 후 읽음 처리: read=true, 미읽음 0")
    void getNotificationsAndMarkRead() {
        notificationService.sendMessage(ACADEMY, sendReq("INDIVIDUAL", List.of(studentId1)));

        List<NotificationDto.NotificationResponse> list =
                notificationService.getNotifications(ACADEMY, null, authKey1);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).isRead()).isFalse();
        assertThat(notificationService.countUnread(ACADEMY, studentId1, null)).isEqualTo(1);

        NotificationDto.NotificationResponse read =
                notificationService.markRead(ACADEMY, list.get(0).getId(),
                        NotificationDto.ReadRequest.builder().authKey(authKey1).build());
        assertThat(read.isRead()).isTrue();
        assertThat(notificationService.countUnread(ACADEMY, null, authKey1)).isZero();
    }

    @Test
    @DisplayName("다른 학생의 알림을 읽음 처리하면 400")
    void markReadOtherStudentsNotification_throws400() {
        notificationService.sendMessage(ACADEMY, sendReq("INDIVIDUAL", List.of(studentId1)));
        Long notificationId = notificationService.getNotifications(ACADEMY, studentId1, null)
                .get(0).getId();

        assertThatThrownBy(() -> notificationService.markRead(ACADEMY, notificationId,
                NotificationDto.ReadRequest.builder().studentId(studentId2).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 출결 연동 =====

    @Test
    @DisplayName("키패드 등원 시 등원 알림 자동 발송 (출결 설정 문구 {name} 치환)")
    void keypadCheckIn_sendsCheckInNotification() {
        attendanceService.keypadCheck(ACADEMY,
                AttendanceDto.KeypadRequest.builder().authKey(authKey1).build());

        List<NotificationDto.NotificationResponse> list =
                notificationService.getNotifications(ACADEMY, studentId1, null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getType()).isEqualTo("CHECK_IN");
        assertThat(list.get(0).getTitle()).contains("홍학생");
        assertThat(list.get(0).getContent()).contains("홍학생");
        assertThat(list.get(0).getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("키패드 하원 시 하원 알림 자동 발송")
    void keypadCheckOut_sendsCheckOutNotification() {
        attendanceService.keypadCheck(ACADEMY,
                AttendanceDto.KeypadRequest.builder().authKey(authKey1).build());
        attendanceService.keypadCheck(ACADEMY,
                AttendanceDto.KeypadRequest.builder().authKey(authKey1).build());

        List<NotificationDto.NotificationResponse> list =
                notificationService.getNotifications(ACADEMY, studentId1, null);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getType()).isEqualTo("CHECK_OUT");
        assertThat(list.get(0).getTitle()).startsWith("[하원]");
    }

    @Test
    @DisplayName("등원 알림 OFF 설정 시 키패드 등원해도 알림 없음")
    void checkInSettingOff_noNotification() {
        notificationService.updateSettings(ACADEMY, NotificationDto.SettingsRequest.builder()
                .studentId(studentId1)
                .settings(List.of(NotificationDto.SettingUpdate.builder()
                        .type("CHECK_IN").enabled(false).build()))
                .build());

        attendanceService.keypadCheck(ACADEMY,
                AttendanceDto.KeypadRequest.builder().authKey(authKey1).build());

        assertThat(notificationService.getNotifications(ACADEMY, studentId1, null)).isEmpty();
        // 알림은 생략되어도 출결 기록 자체는 정상 저장됨
        assertThat(attendanceEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("출결 정정으로 등·하원 구분이 바뀌면 재알림 발송")
    void correctionTypeChange_resendsNotification() {
        AttendanceDto.CheckResponse created = attendanceService.check(ACADEMY,
                AttendanceDto.CheckRequest.builder()
                        .studentId(studentId1).eventType("ABSENT").build());
        // 결석 기록은 알림 없음
        assertThat(notificationService.getNotifications(ACADEMY, studentId1, null)).isEmpty();

        attendanceService.correctEvent(ACADEMY, created.getId(),
                AttendanceDto.CorrectRequest.builder()
                        .eventType("CHECK_IN").reason("기록 오류 정정").build());

        List<NotificationDto.NotificationResponse> list =
                notificationService.getNotifications(ACADEMY, studentId1, null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getType()).isEqualTo("CHECK_IN");
    }

    // ===== 학원 격리 =====

    @Test
    @DisplayName("발송 이력은 학원별로 격리")
    void historyIsolatedByAcademy() {
        notificationService.sendMessage(ACADEMY, sendReq("INDIVIDUAL", List.of(studentId1)));

        StudentDto.DetailResponse other = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원생").build());
        notificationService.sendMessage(OTHER_ACADEMY,
                sendReq("INDIVIDUAL", List.of(other.getId())));

        List<NotificationDto.HistoryResponse> history = notificationService.getHistory(ACADEMY);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAcademyId()).isEqualTo(ACADEMY);
        assertThat(notificationService.getNotifications(OTHER_ACADEMY, other.getId(), null))
                .hasSize(1);
    }
}
