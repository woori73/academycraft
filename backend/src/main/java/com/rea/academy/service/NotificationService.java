package com.rea.academy.service;

import com.rea.academy.domain.attendance.AttendanceEventType;
import com.rea.academy.domain.attendance.ReaAttendanceSetting;
import com.rea.academy.domain.attendance.ReaAttendanceSettingRepository;
import com.rea.academy.domain.clazz.ClassEnrollmentRepository;
import com.rea.academy.domain.clazz.ReaClass;
import com.rea.academy.domain.clazz.ReaClassRepository;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.domain.notification.*;
import com.rea.academy.dto.NotificationDto;
import com.rea.academy.security.ParentScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 알림 관리 서비스.
 * - 메시지 발송 (개별 학부모 / 반 전체 / 학원 전체)
 * - 출결 이벤트 알림 (AttendanceService 연동 — CHECK_IN/CHECK_OUT만)
 * - 발송 이력 기록 (수신자·채널·상태·실패사유·재시도횟수)
 * - 실패 건 재시도, 수신자별 알림 목록·읽음 처리
 * - 학부모(학생)별 알림 종류 ON/OFF 설정
 *
 * 발송은 {@link NotificationSender} 추상화를 통하며, 3단계 구현체는
 * {@link MockNotificationSender} 하나뿐이다. 실제 외부 발송을 하지 않는다.
 *
 * 격리 규칙: 모든 조회·수정은 academyId로 스코핑. 타 학원 데이터 접근 불가.
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final ReaNotificationRepository notificationRepository;
    private final ReaNotificationSettingRepository settingRepository;
    private final ReaStudentRepository studentRepository;
    private final ReaClassRepository classRepository;
    private final ClassEnrollmentRepository enrollmentRepository;
    private final ReaAttendanceSettingRepository attendanceSettingRepository;
    private final NotificationSender notificationSender;

    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 메시지 발송 기본 채널: 앱 푸시 (비용 0원) */
    private static final NotificationChannel DEFAULT_CHANNEL = NotificationChannel.PUSH;

    // ===== 메시지 발송 =====

    /**
     * 메시지 발송 (원장·강사).
     * targetType: INDIVIDUAL(학생 ID 목록) / CLASS(반 ID 목록) / ALL(학원 전체).
     * 알림 종류는 NOTICE. 수신 거부(OFF) 설정된 학부모는 생략한다.
     */
    @Transactional
    public NotificationDto.SendResponse sendMessage(Long academyId, NotificationDto.SendRequest req) {
        NotificationTargetType targetType = NotificationTargetType.parse(req.getTargetType());
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("제목은 필수입니다.");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("내용은 필수입니다.");
        }

        List<ReaStudent> recipients = resolveRecipients(academyId, targetType, req.getTargetIds());

        int sent = 0, failed = 0, skipped = 0;
        List<Long> notificationIds = new ArrayList<>();
        for (ReaStudent student : recipients) {
            if (!isEnabled(academyId, student.getId(), NotificationType.NOTICE)) {
                skipped++;
                continue;
            }
            ReaNotification notification = sendOne(
                    academyId, student, DEFAULT_CHANNEL, NotificationType.NOTICE,
                    req.getTitle().trim(), req.getContent().trim());
            notificationIds.add(notification.getId());
            if (notification.getStatus() == NotificationStatus.SENT) sent++;
            else failed++;
        }

        return NotificationDto.SendResponse.builder()
                .requestedCount(recipients.size())
                .sentCount(sent)
                .failedCount(failed)
                .skippedCount(skipped)
                .notificationIds(notificationIds)
                .build();
    }

    /**
     * 개별 학부모(학생)에게 지정한 종류의 알림 1건을 직접 발송한다. (확장 메서드 — 5단계)
     * 5단계 상담 답변(CONSULT_REPLY) 등 단일 수신자·단일 종류 발송에 사용.
     * 학부모가 해당 종류를 OFF했으면 발송을 생략(skipped)한다.
     * 발송 채널 예외는 내부에서 FAILED로 기록하며 호출자에게 전파하지 않는다
     * (상담 메시지 저장 등 호출 측 업무가 알림 실패로 막히지 않도록).
     */
    @Transactional
    public NotificationDto.SendResponse sendToStudent(
            Long academyId, Long studentId, NotificationType type, String title, String content) {
        if (type == null) {
            throw new IllegalArgumentException("알림 종류는 필수입니다.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("제목은 필수입니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("내용은 필수입니다.");
        }
        ReaStudent student = requireStudent(academyId, studentId, null);
        if (!isEnabled(academyId, student.getId(), type)) {
            return NotificationDto.SendResponse.builder()
                    .requestedCount(1)
                    .sentCount(0)
                    .failedCount(0)
                    .skippedCount(1)
                    .notificationIds(List.of())
                    .build();
        }
        ReaNotification notification = sendOne(
                academyId, student, DEFAULT_CHANNEL, type, title.trim(), content.trim());
        int sent = notification.getStatus() == NotificationStatus.SENT ? 1 : 0;
        return NotificationDto.SendResponse.builder()
                .requestedCount(1)
                .sentCount(sent)
                .failedCount(sent == 1 ? 0 : 1)
                .skippedCount(0)
                .notificationIds(List.of(notification.getId()))
                .build();
    }

    /**
     * 출결 알림 발송 (AttendanceService에서 호출).
     * CHECK_IN/LATE → 등원 알림, CHECK_OUT/EARLY_LEAVE → 하원 알림.
     * 문구는 출결 설정의 checkInMessage/checkOutMessage ({name} → 학생 이름 치환).
     * ABSENT 등 그 외 구분은 알림을 보내지 않는다.
     */
    @Transactional
    public void sendAttendanceNotification(
            Long academyId, Long studentId, AttendanceEventType eventType) {
        NotificationType type;
        if (eventType == AttendanceEventType.CHECK_IN || eventType == AttendanceEventType.LATE) {
            type = NotificationType.CHECK_IN;
        } else if (eventType == AttendanceEventType.CHECK_OUT
                || eventType == AttendanceEventType.EARLY_LEAVE) {
            type = NotificationType.CHECK_OUT;
        } else {
            return; // ABSENT 등: 알림 없음
        }

        ReaStudent student = requireStudent(academyId, studentId, null);
        if (!isEnabled(academyId, student.getId(), type)) {
            return; // 학부모가 해당 알림을 OFF: 발송 생략
        }

        ReaAttendanceSetting setting = attendanceSettingRepository.findByAcademyId(academyId)
                .orElseGet(() -> ReaAttendanceSetting.defaults(academyId));
        String template = type == NotificationType.CHECK_IN
                ? setting.getCheckInMessage() : setting.getCheckOutMessage();
        String content = template.replace("{name}", student.getName());
        String title = (type == NotificationType.CHECK_IN ? "[등원] " : "[하원] ") + student.getName();

        sendOne(academyId, student, DEFAULT_CHANNEL, type, title, content);
    }

    // ===== 알림 목록 (수신자) =====

    /** 수신자(학부모=학생)별 알림 목록 — 최신순. */
    public List<NotificationDto.NotificationResponse> getNotifications(
            Long academyId, Long studentId, String authKey) {
        // 학부모(학생 키 로그인)는 본인 알림만 조회 — 파라미터 위조 시 본인으로 강제.
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(studentId, authKey);
        ReaStudent student = requireStudent(academyId, ref.studentId(), ref.authKey());
        return notificationRepository
                .findByAcademyIdAndRecipientStudentIdOrderByCreatedAtDesc(academyId, student.getId())
                .stream()
                .map(this::toNotificationResponse)
                .collect(Collectors.toList());
    }

    /** 미읽음 건수 */
    public long countUnread(Long academyId, Long studentId, String authKey) {
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(studentId, authKey);
        ReaStudent student = requireStudent(academyId, ref.studentId(), ref.authKey());
        return notificationRepository
                .countByAcademyIdAndRecipientStudentIdAndReadAtIsNull(academyId, student.getId());
    }

    // ===== 읽음 처리 =====

    /**
     * 알림 읽음 처리. 본인(수신 학생) 알림만 처리 가능.
     */
    @Transactional
    public NotificationDto.NotificationResponse markRead(
            Long academyId, Long notificationId, NotificationDto.ReadRequest req) {
        ReaStudent student = requireStudent(academyId, req.getStudentId(), req.getAuthKey());
        ReaNotification notification = notificationRepository.findById(notificationId)
                .filter(n -> n.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        if (!notification.getRecipientStudentId().equals(student.getId())) {
            throw new IllegalArgumentException("본인의 알림만 읽음 처리할 수 있습니다.");
        }
        notification.markRead();
        return toNotificationResponse(notification);
    }

    // ===== 발송 이력 (관리자) =====

    /** 학원 전체 발송 이력 — 최신순. */
    public List<NotificationDto.HistoryResponse> getHistory(Long academyId) {
        return notificationRepository.findByAcademyIdOrderByCreatedAtDesc(academyId).stream()
                .map(this::toHistoryResponse)
                .collect(Collectors.toList());
    }

    // ===== 재시도 =====

    /**
     * 실패 건 재시도. FAILED 상태에서만 가능. 재시도 횟수 +1.
     */
    @Transactional
    public NotificationDto.HistoryResponse retry(Long academyId, Long notificationId) {
        ReaNotification notification = notificationRepository.findById(notificationId)
                .filter(n -> n.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new IllegalStateException("실패한 알림만 재시도할 수 있습니다.");
        }
        notification.increaseRetryCount();
        NotificationSendResult result = notificationSender.send(
                notification.getChannel(),
                notification.getRecipientName(),
                notification.getRecipientPhone(),
                notification.getTitle(),
                notification.getContent());
        if (result.isOk()) {
            notification.markSent();
        } else {
            notification.markFailed(result.getFailReason());
        }
        return toHistoryResponse(notification);
    }

    // ===== 알림 설정 =====

    /** 학부모(학생)별 알림 종류 ON/OFF 설정 조회. 미설정 종류는 ON. */
    public NotificationDto.SettingsResponse getSettings(
            Long academyId, Long studentId, String authKey) {
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(studentId, authKey);
        ReaStudent student = requireStudent(academyId, ref.studentId(), ref.authKey());
        List<NotificationDto.SettingEntry> entries = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            entries.add(NotificationDto.SettingEntry.builder()
                    .type(type.name())
                    .typeLabel(type.getLabel())
                    .enabled(isEnabled(academyId, student.getId(), type))
                    .build());
        }
        return NotificationDto.SettingsResponse.builder()
                .academyId(academyId)
                .studentId(student.getId())
                .studentName(student.getName())
                .settings(entries)
                .build();
    }

    /** 학부모(학생)별 알림 종류 ON/OFF 설정 변경 (upsert). */
    @Transactional
    public NotificationDto.SettingsResponse updateSettings(
            Long academyId, NotificationDto.SettingsRequest req) {
        ReaStudent student = requireStudent(academyId, req.getStudentId(), req.getAuthKey());
        if (req.getSettings() == null || req.getSettings().isEmpty()) {
            throw new IllegalArgumentException("변경할 설정이 없습니다.");
        }
        for (NotificationDto.SettingUpdate update : req.getSettings()) {
            NotificationType type = NotificationType.parse(update.getType());
            if (update.getEnabled() == null) {
                throw new IllegalArgumentException("수신 여부(enabled)는 필수입니다: " + type.name());
            }
            ReaNotificationSetting setting = settingRepository
                    .findByAcademyIdAndStudentIdAndType(academyId, student.getId(), type)
                    .orElseGet(() -> settingRepository.save(ReaNotificationSetting.builder()
                            .academyId(academyId)
                            .studentId(student.getId())
                            .type(type)
                            .build()));
            setting.updateEnabled(update.getEnabled());
        }
        return getSettings(academyId, student.getId(), null);
    }

    // ===== 발송 대상 판별 =====

    private List<ReaStudent> resolveRecipients(
            Long academyId, NotificationTargetType targetType, List<Long> targetIds) {
        Set<Long> studentIds = new LinkedHashSet<>();
        switch (targetType) {
            case INDIVIDUAL -> {
                if (targetIds == null || targetIds.isEmpty()) {
                    throw new IllegalArgumentException("개별 발송 대상 학생 ID가 필요합니다.");
                }
                for (Long studentId : targetIds) {
                    ReaStudent student = studentRepository.findById(studentId)
                            .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "학생을 찾을 수 없습니다: " + studentId));
                    studentIds.add(student.getId());
                }
                return studentIds.stream()
                        .map(id -> studentRepository.findById(id).orElseThrow())
                        .collect(Collectors.toList());
            }
            case CLASS -> {
                if (targetIds == null || targetIds.isEmpty()) {
                    throw new IllegalArgumentException("반별 발송 대상 반 ID가 필요합니다.");
                }
                for (Long classId : targetIds) {
                    ReaClass clazz = classRepository.findById(classId)
                            .filter(c -> c.getAcademyId().equals(academyId))
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "반을 찾을 수 없습니다: " + classId));
                    enrollmentRepository.findByClassIdAndActiveTrue(clazz.getId()).stream()
                            .map(e -> studentRepository.findById(e.getStudentId()).orElse(null))
                            .filter(s -> s != null && s.getActive()
                                    && s.getAcademyId().equals(academyId))
                            .map(ReaStudent::getId)
                            .forEach(studentIds::add);
                }
                return studentIds.stream()
                        .map(id -> studentRepository.findById(id).orElseThrow())
                        .collect(Collectors.toList());
            }
            case ALL -> {
                return studentRepository.findByAcademyIdAndActiveTrue(academyId);
            }
            default -> throw new IllegalArgumentException("알 수 없는 발송 대상 종류입니다.");
        }
    }

    // ===== 발송 실행 =====

    /**
     * 알림 1건 발송 + 이력 기록.
     * 발송 채널 예외가 발생해도 호출자에게 전파하지 않고 FAILED로 기록한다
     * (출결 기록 등 호출 측 업무가 알림 실패로 막히지 않도록).
     */
    private ReaNotification sendOne(Long academyId,
                                    ReaStudent student,
                                    NotificationChannel channel,
                                    NotificationType type,
                                    String title,
                                    String content) {
        String recipientName = student.getParentName() != null && !student.getParentName().isBlank()
                ? student.getParentName() + " 학부모(" + student.getName() + ")"
                : student.getName() + " 학부모";
        ReaNotification notification = ReaNotification.builder()
                .academyId(academyId)
                .recipientStudentId(student.getId())
                .recipientName(recipientName)
                .recipientPhone(student.getParentPhone())
                .channel(channel)
                .type(type)
                .title(title)
                .content(content)
                .status(NotificationStatus.SENT)
                .build();
        try {
            NotificationSendResult result = notificationSender.send(
                    channel, recipientName, student.getParentPhone(), title, content);
            if (result.isOk()) {
                notification.markSent();
            } else {
                notification.markFailed(result.getFailReason());
            }
        } catch (Exception e) {
            notification.markFailed("발송 중 오류: " + e.getMessage());
        }
        return notificationRepository.save(notification);
    }

    /** 해당 종류 알림 수신 여부. 미설정 시 기본 ON. */
    private boolean isEnabled(Long academyId, Long studentId, NotificationType type) {
        return settingRepository.findByAcademyIdAndStudentIdAndType(academyId, studentId, type)
                .map(ReaNotificationSetting::getEnabled)
                .orElse(true);
    }

    /**
     * 학생 조회: studentId 또는 authKey 중 하나로 같은 학원의 활성 학생을 찾는다.
     * (AttendanceService.requireStudent와 동일한 격리 규칙)
     */
    private ReaStudent requireStudent(Long academyId, Long studentId, String authKey) {
        if (studentId != null) {
            return studentRepository.findById(studentId)
                    .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                    .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));
        }
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalArgumentException("학생 ID 또는 인증키 중 하나는 필수입니다.");
        }
        return studentRepository.findByAuthKeyAndActiveTrue(authKey.trim())
                .filter(s -> s.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("인증키가 유효하지 않습니다."));
    }

    // ===== DTO 변환 =====

    private NotificationDto.NotificationResponse toNotificationResponse(ReaNotification n) {
        return NotificationDto.NotificationResponse.builder()
                .id(n.getId())
                .academyId(n.getAcademyId())
                .recipientStudentId(n.getRecipientStudentId())
                .recipientName(n.getRecipientName())
                .channel(n.getChannel().name())
                .type(n.getType().name())
                .typeLabel(n.getType().getLabel())
                .title(n.getTitle())
                .content(n.getContent())
                .status(n.getStatus().name())
                .read(n.isRead())
                .createdAt(n.getCreatedAt().format(DATETIME_FORMAT))
                .build();
    }

    private NotificationDto.HistoryResponse toHistoryResponse(ReaNotification n) {
        return NotificationDto.HistoryResponse.builder()
                .id(n.getId())
                .academyId(n.getAcademyId())
                .recipientStudentId(n.getRecipientStudentId())
                .recipientName(n.getRecipientName())
                .recipientPhone(n.getRecipientPhone())
                .channel(n.getChannel().name())
                .type(n.getType().name())
                .typeLabel(n.getType().getLabel())
                .title(n.getTitle())
                .content(n.getContent())
                .status(n.getStatus().name())
                .failReason(n.getFailReason())
                .retryCount(n.getRetryCount() == null ? 0 : n.getRetryCount())
                .read(n.isRead())
                .createdAt(n.getCreatedAt().format(DATETIME_FORMAT))
                .build();
    }
}
