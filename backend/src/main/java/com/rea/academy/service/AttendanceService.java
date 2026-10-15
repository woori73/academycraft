package com.rea.academy.service;

import com.rea.academy.domain.attendance.*;
import com.rea.academy.domain.clazz.ClassEnrollmentRepository;
import com.rea.academy.domain.clazz.ReaClass;
import com.rea.academy.domain.clazz.ReaClassRepository;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.security.ParentScope;
import com.rea.academy.security.ReaForbiddenException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 출결 관리 서비스.
 * - 출결 이벤트 기록 (강사 직접 기록 · 키패드 자동 판별)
 * - 반별 출결부 · 학생별 이력 · 월별 현황 조회
 * - 출결 정정 (정정 이력 보존)
 * - 학원별 출결 설정 (하원 가능 시간·사진 전송·알림 문구)
 *
 * 격리 규칙: 모든 조회·수정은 academyId로 스코핑. 타 학원 데이터 접근 불가.
 * 권한: 인증 미구현 단계이므로 academyId 스코핑으로 격리.
 *
 * 알림: 3단계에서 연동 완료 — 출결 기록·키패드·정정(구분 변경 시) 지점에서
 * NotificationService를 호출해 학부모에게 등·하원 알림을 발송한다 (Mock 채널, 실제 발송 없음).
 * 알림 실패는 출결 기록에 영향을 주지 않는다.
 *
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceService {

    private final ReaAttendanceEventRepository eventRepository;
    private final ReaAttendanceCorrectionRepository correctionRepository;
    private final ReaAttendanceSettingRepository settingRepository;
    private final ReaStudentRepository studentRepository;
    private final ReaClassRepository classRepository;
    private final ClassEnrollmentRepository enrollmentRepository;
    /** 3단계: 출결 알림 발송 연동 (Mock 채널, 실제 발송 없음) */
    private final NotificationService notificationService;

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final EnumSet<AttendanceEventType> IN_TYPES =
            EnumSet.of(AttendanceEventType.CHECK_IN, AttendanceEventType.LATE);
    private static final EnumSet<AttendanceEventType> OUT_TYPES =
            EnumSet.of(AttendanceEventType.CHECK_OUT, AttendanceEventType.EARLY_LEAVE);

    // ===== 출결 기록 =====

    /**
     * 출결 기록 (강사·원장 직접 기록).
     * studentId 또는 authKey 중 하나 필수. eventType 필수.
     */
    @Transactional
    public AttendanceDto.CheckResponse check(Long academyId, AttendanceDto.CheckRequest req) {
        ReaStudent student = requireStudent(academyId, req.getStudentId(), req.getAuthKey());
        AttendanceEventType type = AttendanceEventType.parse(req.getEventType());
        LocalDateTime occurredAt = parseDateTimeOrNull(req.getOccurredAt());
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        validateCheckOutTime(academyId, type, occurredAt);

        ReaAttendanceEvent event = ReaAttendanceEvent.builder()
                .academyId(academyId)
                .studentId(student.getId())
                .classId(currentClassIdOf(student.getId()))
                .eventType(type)
                .occurredAt(occurredAt)
                .source(AttendanceSource.MANUAL)
                .note(blankToNull(req.getNote()))
                .build();
        eventRepository.save(event);

        // [3단계] 출결 알림 발송: 등원/하원 시 학부모에게 실시간 알림 (Mock 채널, 실제 발송 없음).
        // 알림 실패가 출결 기록을 막지 않도록 예외를 격리한다.
        notifyAttendance(academyId, student.getId(), type);

        return toCheckResponse(event, student.getName(), false);
    }

    /**
     * 키패드 출결 기록.
     * 학생이 출석키(= 학생 인증키)를 입력하면 당일 첫 기록은 등원(CHECK_IN),
     * 등원 이후 기록은 하원(CHECK_OUT)으로 자동 판별한다.
     * 당일 등·하원이 모두 기록된 상태에서는 409.
     *
     * <p>보안: X-Auth-Key 헤더(학생 키) 인증이 유지되며, 본문에 담긴 출석키가
     * 헤더 인증 학생 본인의 것이 아니면 403 (타 학생 출결 위조 차단).</p>
     */
    @Transactional
    public AttendanceDto.CheckResponse keypadCheck(Long academyId, AttendanceDto.KeypadRequest req) {
        if (req.getAuthKey() == null || req.getAuthKey().isBlank()) {
            throw new IllegalArgumentException("출석키를 입력하세요.");
        }
        ReaStudent student = requireStudent(academyId, null, req.getAuthKey());
        Long ownId = ParentScope.ownStudentIdOrNull();
        if (ownId != null && !ownId.equals(student.getId())) {
            throw new ReaForbiddenException("본인의 출석키로만 기록할 수 있습니다.");
        }

        LocalDate today = LocalDate.now();
        List<ReaAttendanceEvent> todayEvents = eventsOfDay(academyId, student.getId(), today);

        boolean hasIn = todayEvents.stream().anyMatch(e -> IN_TYPES.contains(e.getEventType()));
        boolean hasOut = todayEvents.stream().anyMatch(e -> OUT_TYPES.contains(e.getEventType()));

        AttendanceEventType type;
        if (!hasIn) {
            type = AttendanceEventType.CHECK_IN;
        } else if (!hasOut) {
            type = AttendanceEventType.CHECK_OUT;
        } else {
            throw new IllegalStateException("오늘 등·하원 기록이 이미 완료되었습니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        validateCheckOutTime(academyId, type, now);

        ReaAttendanceEvent event = ReaAttendanceEvent.builder()
                .academyId(academyId)
                .studentId(student.getId())
                .classId(currentClassIdOf(student.getId()))
                .eventType(type)
                .occurredAt(now)
                .source(AttendanceSource.KEYPAD)
                .build();
        eventRepository.save(event);

        // [3단계] 등원/하원 실시간 알림 발송 (학부모 앱 푸시 — Mock 채널, 실제 발송 없음).
        notifyAttendance(academyId, student.getId(), type);

        return toCheckResponse(event, student.getName(), true);
    }

    // ===== 반별 출결부 =====

    /**
     * 반별 출결부: 특정 반·특정 일자의 전체 학생 출결 상태 목록.
     * 현재 활성 배정 기준 소속 학생을 대상으로 한다.
     */
    public AttendanceDto.RosterResponse getRoster(Long academyId, Long classId, String dateStr) {
        ReaClass clazz = findClassInAcademy(academyId, classId);
        LocalDate date = parseDate(dateStr);

        List<AttendanceDto.RosterEntry> entries = enrollmentRepository
                .findByClassIdAndActiveTrue(classId).stream()
                .map(e -> studentRepository.findById(e.getStudentId()).orElse(null))
                .filter(s -> s != null && s.getActive() && s.getAcademyId().equals(academyId))
                .sorted(Comparator.comparing(ReaStudent::getName))
                .map(s -> toRosterEntry(academyId, s, date))
                .collect(Collectors.toList());

        return AttendanceDto.RosterResponse.builder()
                .classId(clazz.getId())
                .className(clazz.getName())
                .date(date.toString())
                .entries(entries)
                .build();
    }

    // ===== 학생별 이력 =====

    /**
     * 학생별·기간별 출결 기록 조회.
     * from/to 미지정 시 최근 30일.
     */
    public List<AttendanceDto.EventResponse> getStudentHistory(
            Long academyId, Long studentId, String fromStr, String toStr) {
        ReaStudent student = requireStudent(academyId, studentId, null);

        LocalDate to = toStr == null ? LocalDate.now() : parseDate(toStr);
        LocalDate from = fromStr == null ? to.minusDays(29) : parseDate(fromStr);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }

        List<ReaAttendanceEvent> events = eventRepository
                .findByAcademyIdAndStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        academyId, student.getId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        return events.stream()
                .map(e -> toEventResponse(academyId, e, student.getName(), true))
                .collect(Collectors.toList());
    }

    // ===== 월별 현황 =====

    /**
     * 월별 출결 현황: 반 소속 학생별 일자별 상태 + 집계(출석·결석·지각·조퇴일수).
     */
    public AttendanceDto.MonthlyResponse getMonthly(
            Long academyId, Long classId, String yearMonthStr) {
        ReaClass clazz = findClassInAcademy(academyId, classId);
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(yearMonthStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("월 형식이 잘못되었습니다 (yyyy-MM): " + yearMonthStr);
        }
        int daysInMonth = yearMonth.lengthOfMonth();

        List<AttendanceDto.MonthlyStudent> students = enrollmentRepository
                .findByClassIdAndActiveTrue(classId).stream()
                .map(e -> studentRepository.findById(e.getStudentId()).orElse(null))
                .filter(s -> s != null && s.getActive() && s.getAcademyId().equals(academyId))
                .sorted(Comparator.comparing(ReaStudent::getName))
                .map(s -> toMonthlyStudent(academyId, s, yearMonth, daysInMonth))
                .collect(Collectors.toList());

        return AttendanceDto.MonthlyResponse.builder()
                .classId(clazz.getId())
                .className(clazz.getName())
                .yearMonth(yearMonth.toString())
                .daysInMonth(daysInMonth)
                .students(students)
                .build();
    }

    // ===== 출결 정정 =====

    /**
     * 출결 정정: 잘못된 기록 수정. 정정 사유 필수.
     * 원본 행을 수정하고 정정 전·후 값을 정정 이력 테이블에 남긴다.
     */
    @Transactional
    public AttendanceDto.EventResponse correctEvent(
            Long academyId, Long eventId, AttendanceDto.CorrectRequest req) {
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new IllegalArgumentException("정정 사유는 필수입니다.");
        }
        ReaAttendanceEvent event = eventRepository.findById(eventId)
                .filter(e -> e.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("출결 기록을 찾을 수 없습니다."));
        AttendanceEventType beforeType = event.getEventType();

        AttendanceEventType afterType = req.getEventType() == null
                ? event.getEventType() : AttendanceEventType.parse(req.getEventType());
        LocalDateTime afterOccurredAt = req.getOccurredAt() == null
                ? event.getOccurredAt() : parseDateTime(req.getOccurredAt());
        String afterNote = req.getNote();
        validateCheckOutTime(academyId, afterType, afterOccurredAt);

        correctionRepository.save(ReaAttendanceCorrection.builder()
                .academyId(academyId)
                .eventId(event.getId())
                .beforeType(event.getEventType())
                .afterType(afterType)
                .beforeOccurredAt(event.getOccurredAt())
                .afterOccurredAt(afterOccurredAt)
                .beforeNote(event.getNote())
                .afterNote(afterNote)
                .reason(req.getReason().trim())
                .build());

        event.correct(afterType, afterOccurredAt, afterNote);

        // [3단계] 정정 알림: 등·하원 구분이 바뀐 경우에만 학부모에게 재알림한다.
        // (같은 구분 내 시각·메모 정정은 중복 알림 방지를 위해 발송하지 않음)
        if (!beforeType.equals(afterType)
                && (afterType == AttendanceEventType.CHECK_IN
                    || afterType == AttendanceEventType.CHECK_OUT)) {
            notifyAttendance(academyId, event.getStudentId(), afterType);
        }

        String studentName = studentRepository.findById(event.getStudentId())
                .map(ReaStudent::getName).orElse("-");
        return toEventResponse(academyId, event, studentName, true);
    }

    // ===== 출결 설정 =====

    /** 학원의 출결 설정 조회. 미설정 시 기본값 반환 (저장하지 않음). */
    public AttendanceDto.SettingsResponse getSettings(Long academyId) {
        ReaAttendanceSetting setting = settingRepository.findByAcademyId(academyId)
                .orElseGet(() -> ReaAttendanceSetting.defaults(academyId));
        return toSettingsResponse(setting);
    }

    /** 학원의 출결 설정 변경 (upsert). */
    @Transactional
    public AttendanceDto.SettingsResponse updateSettings(
            Long academyId, AttendanceDto.SettingsRequest req) {
        ReaAttendanceSetting setting = settingRepository.findByAcademyId(academyId)
                .orElseGet(() -> settingRepository.save(ReaAttendanceSetting.defaults(academyId)));

        LocalTime earliest = parseTimeOrNull(req.getCheckOutEarliestTime());
        LocalTime latest = parseTimeOrNull(req.getCheckOutLatestTime());
        if (earliest != null && latest != null && !earliest.isBefore(latest)) {
            throw new IllegalArgumentException("하원 가능 시작 시각은 종료 시각보다 빨라야 합니다.");
        }
        setting.update(earliest, latest, req.getPhotoEnabled(),
                req.getCheckInMessage(), req.getCheckOutMessage());
        return toSettingsResponse(setting);
    }

    // ===== 검증·조회 헬퍼 =====

    /**
     * 출결 알림 발송 (3단계).
     * 알림 실패가 출결 기록 트랜잭션을 깨뜨리지 않도록 예외를 격리한다.
     */
    private void notifyAttendance(Long academyId, Long studentId, AttendanceEventType type) {
        try {
            notificationService.sendAttendanceNotification(academyId, studentId, type);
        } catch (Exception e) {
            log.warn("출결 알림 발송 실패 (출결 기록은 유지): academyId={}, studentId={}, type={}",
                    academyId, studentId, type, e);
        }
    }

    /** 학원 내 반 조회 (타 학원 접근 불가) */
    private ReaClass findClassInAcademy(Long academyId, Long classId) {
        return classRepository.findById(classId)
                .filter(c -> c.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("반을 찾을 수 없습니다."));
    }

    /**
     * 학생 조회: studentId 또는 authKey 중 하나로 같은 학원의 활성 학생을 찾는다.
     * authKey로 찾았으나 타 학원 소속이면 400 (정보 누출 방지).
     */
    private ReaStudent requireStudent(Long academyId, Long studentId, String authKey) {
        if (studentId != null) {
            return studentRepository.findById(studentId)
                    .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                    .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));
        }
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalArgumentException("학생 ID 또는 출석키 중 하나는 필수입니다.");
        }
        return studentRepository.findByAuthKeyAndActiveTrue(authKey.trim())
                .filter(s -> s.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("출석키가 유효하지 않습니다."));
    }

    /** 하원 가능 시간 검증: CHECK_OUT/EARLY_LEAVE는 설정 시작 시각 이전에 기록 불가 */
    private void validateCheckOutTime(
            Long academyId, AttendanceEventType type, LocalDateTime occurredAt) {
        if (!OUT_TYPES.contains(type)) {
            return;
        }
        LocalTime earliest = settingRepository.findByAcademyId(academyId)
                .map(ReaAttendanceSetting::getCheckOutEarliestTime)
                .orElse(null);
        if (earliest != null && occurredAt.toLocalTime().isBefore(earliest)) {
            throw new IllegalStateException(
                    "하원 가능 시간(" + earliest.format(TIME_FORMAT) + ") 전에는 하원 기록을 할 수 없습니다.");
        }
    }

    /** 학생의 현재 활성 배정 중 첫 번째 반 ID (기록 시점 스냅샷용) */
    private Long currentClassIdOf(Long studentId) {
        return enrollmentRepository.findByStudentIdAndActiveTrue(studentId).stream()
                .findFirst()
                .map(e -> e.getClassId())
                .orElse(null);
    }

    private List<ReaAttendanceEvent> eventsOfDay(Long academyId, Long studentId, LocalDate date) {
        return eventRepository.findByAcademyIdAndStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                academyId, studentId, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    // ===== 상태 판정 =====

    private static boolean hasType(List<ReaAttendanceEvent> events, AttendanceEventType type) {
        return events.stream().anyMatch(e -> e.getEventType() == type);
    }

    /** 반별 출결부용 당일 상태 코드 */
    private static String rosterStatus(List<ReaAttendanceEvent> events) {
        if (hasType(events, AttendanceEventType.CHECK_OUT)) return "CHECKED_OUT";
        if (hasType(events, AttendanceEventType.EARLY_LEAVE)) return "EARLY_LEAVE";
        if (hasType(events, AttendanceEventType.LATE)) return "LATE";
        if (hasType(events, AttendanceEventType.CHECK_IN)) return "PRESENT";
        if (hasType(events, AttendanceEventType.ABSENT)) return "ABSENT";
        return "NONE";
    }

    /** 월별 현황용 일자 상태 코드 */
    private static String monthlyDayStatus(List<ReaAttendanceEvent> events) {
        if (hasType(events, AttendanceEventType.CHECK_OUT)) return "OUT";
        if (hasType(events, AttendanceEventType.EARLY_LEAVE)) return "EARLY_LEAVE";
        if (hasType(events, AttendanceEventType.LATE)) return "LATE";
        if (hasType(events, AttendanceEventType.CHECK_IN)) return "IN";
        if (hasType(events, AttendanceEventType.ABSENT)) return "ABSENT";
        return "NONE";
    }

    private static String earliestOf(List<ReaAttendanceEvent> events, EnumSet<AttendanceEventType> types) {
        return events.stream()
                .filter(e -> types.contains(e.getEventType()))
                .map(ReaAttendanceEvent::getOccurredAt)
                .min(LocalDateTime::compareTo)
                .map(t -> t.format(TIME_FORMAT))
                .orElse(null);
    }

    private static String latestOf(List<ReaAttendanceEvent> events, EnumSet<AttendanceEventType> types) {
        return events.stream()
                .filter(e -> types.contains(e.getEventType()))
                .map(ReaAttendanceEvent::getOccurredAt)
                .max(LocalDateTime::compareTo)
                .map(t -> t.format(TIME_FORMAT))
                .orElse(null);
    }

    // ===== DTO 변환 =====

    private AttendanceDto.CheckResponse toCheckResponse(
            ReaAttendanceEvent e, String studentName, boolean autoDetected) {
        return AttendanceDto.CheckResponse.builder()
                .id(e.getId())
                .academyId(e.getAcademyId())
                .studentId(e.getStudentId())
                .studentName(studentName)
                .eventType(e.getEventType().name())
                .autoDetected(autoDetected)
                .occurredAt(e.getOccurredAt().format(DATETIME_FORMAT))
                .source(e.getSource().name())
                .note(e.getNote())
                .build();
    }

    private AttendanceDto.RosterEntry toRosterEntry(Long academyId, ReaStudent s, LocalDate date) {
        List<ReaAttendanceEvent> events = eventsOfDay(academyId, s.getId(), date);
        return AttendanceDto.RosterEntry.builder()
                .studentId(s.getId())
                .studentName(s.getName())
                .grade(s.getGrade())
                .status(rosterStatus(events))
                .checkInAt(earliestOf(events, IN_TYPES))
                .checkOutAt(latestOf(events, OUT_TYPES))
                .events(events.stream()
                        .map(e -> toEventResponse(academyId, e, s.getName(), false))
                        .collect(Collectors.toList()))
                .build();
    }

    private AttendanceDto.MonthlyStudent toMonthlyStudent(
            Long academyId, ReaStudent s, YearMonth yearMonth, int daysInMonth) {
        Map<Integer, String> daily = new HashMap<>();
        int present = 0, absent = 0, late = 0, earlyLeave = 0;
        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = yearMonth.atDay(day);
            List<ReaAttendanceEvent> events = eventsOfDay(academyId, s.getId(), date);
            String status = monthlyDayStatus(events);
            daily.put(day, status);
            switch (status) {
                case "IN", "OUT" -> present++;
                case "LATE" -> { present++; late++; }
                case "EARLY_LEAVE" -> { present++; earlyLeave++; }
                case "ABSENT" -> absent++;
                default -> { /* NONE: 미집계 */ }
            }
        }
        return AttendanceDto.MonthlyStudent.builder()
                .studentId(s.getId())
                .studentName(s.getName())
                .presentDays(present)
                .absentDays(absent)
                .lateDays(late)
                .earlyLeaveDays(earlyLeave)
                .dailyStatus(daily)
                .build();
    }

    private AttendanceDto.EventResponse toEventResponse(
            Long academyId, ReaAttendanceEvent e, String studentName, boolean withCorrections) {
        List<AttendanceDto.CorrectionResponse> corrections = withCorrections
                ? correctionRepository.findByAcademyIdAndEventIdOrderByCorrectedAtDesc(academyId, e.getId())
                        .stream().map(this::toCorrectionResponse).collect(Collectors.toList())
                : List.of();
        String className = null;
        if (e.getClassId() != null) {
            className = classRepository.findById(e.getClassId())
                    .map(ReaClass::getName).orElse(null);
        }
        return AttendanceDto.EventResponse.builder()
                .id(e.getId())
                .academyId(e.getAcademyId())
                .studentId(e.getStudentId())
                .studentName(studentName)
                .classId(e.getClassId())
                .className(className)
                .eventType(e.getEventType().name())
                .occurredAt(e.getOccurredAt().format(DATETIME_FORMAT))
                .source(e.getSource().name())
                .note(e.getNote())
                .corrected(e.getCorrected())
                .corrections(corrections)
                .build();
    }

    private AttendanceDto.CorrectionResponse toCorrectionResponse(ReaAttendanceCorrection c) {
        return AttendanceDto.CorrectionResponse.builder()
                .id(c.getId())
                .beforeType(c.getBeforeType().name())
                .afterType(c.getAfterType().name())
                .beforeOccurredAt(c.getBeforeOccurredAt().format(DATETIME_FORMAT))
                .afterOccurredAt(c.getAfterOccurredAt().format(DATETIME_FORMAT))
                .beforeNote(c.getBeforeNote())
                .afterNote(c.getAfterNote())
                .reason(c.getReason())
                .correctedAt(c.getCorrectedAt().format(DATETIME_FORMAT))
                .build();
    }

    private AttendanceDto.SettingsResponse toSettingsResponse(ReaAttendanceSetting s) {
        return AttendanceDto.SettingsResponse.builder()
                .academyId(s.getAcademyId())
                .checkOutEarliestTime(formatTime(s.getCheckOutEarliestTime()))
                .checkOutLatestTime(formatTime(s.getCheckOutLatestTime()))
                .photoEnabled(s.getPhotoEnabled())
                .checkInMessage(s.getCheckInMessage())
                .checkOutMessage(s.getCheckOutMessage())
                .build();
    }

    // ===== 파싱 헬퍼 =====

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException("일자(date)는 필수입니다 (yyyy-MM-dd).");
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("일자 형식이 잘못되었습니다 (yyyy-MM-dd): " + date);
        }
    }

    private LocalDateTime parseDateTime(String dateTime) {
        try {
            return LocalDateTime.parse(dateTime.trim(), DATETIME_FORMAT);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "일시 형식이 잘못되었습니다 (yyyy-MM-dd'T'HH:mm): " + dateTime);
        }
    }

    private LocalDateTime parseDateTimeOrNull(String dateTime) {
        if (dateTime == null || dateTime.isBlank()) return null;
        return parseDateTime(dateTime);
    }

    private LocalTime parseTimeOrNull(String time) {
        if (time == null || time.isBlank()) return null;
        try {
            return LocalTime.parse(time.trim(), TIME_FORMAT);
        } catch (Exception e) {
            throw new IllegalArgumentException("시각 형식이 잘못되었습니다 (HH:mm): " + time);
        }
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
