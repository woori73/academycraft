package com.rea.academy.service;

import com.rea.academy.common.ReaTime;
import com.rea.academy.domain.attendance.AttendanceEventType;
import com.rea.academy.domain.attendance.ReaAttendanceEvent;
import com.rea.academy.domain.attendance.ReaAttendanceEventRepository;
import com.rea.academy.domain.clazz.ClassEnrollment;
import com.rea.academy.domain.clazz.ClassEnrollmentRepository;
import com.rea.academy.domain.clazz.ReaClass;
import com.rea.academy.domain.clazz.ReaClassRepository;
import com.rea.academy.domain.learning.*;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.domain.notification.NotificationType;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.NotificationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 학습 관리 서비스 (6단계).
 * - 숙제 등록·검사 (반별/학생별) + 등록 시 HOMEWORK 알림 발송
 * - 학습 진도 기록·조회
 * - 시험·성적 등록·조회 + 등록 시 GRADE 알림 발송
 * - 학습 리포트 데이터 집계 (성적·숙제·진도·출결)
 *
 * 알림 발송은 {@link NotificationService}를 재사용하며, 발송 실패는 격리한다
 * (숙제·성적 등록 등 호출 측 업무가 알림 실패로 막히지 않도록).
 * 실제 외부 발송은 하지 않는다 (MockNotificationSender).
 *
 * 격리 규칙: 모든 조회·수정은 academyId로 스코핑. 타 학원 데이터 접근 불가.
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearningService {

    private final ReaHomeworkRepository homeworkRepository;
    private final ReaHomeworkCheckRepository homeworkCheckRepository;
    private final ReaProgressRepository progressRepository;
    private final ReaGradeRepository gradeRepository;
    private final ReaStudentRepository studentRepository;
    private final ReaClassRepository classRepository;
    private final ClassEnrollmentRepository enrollmentRepository;
    private final ReaAttendanceEventRepository attendanceEventRepository;
    private final NotificationService notificationService;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int NOTIFICATION_CONTENT_MAX = 500;

    // ===== 숙제 등록 =====

    /**
     * 숙제 등록 (강사).
     * classId(반별) / studentId(학생별) 중 최소 하나 필수.
     * 등록 후 대상 학부모에게 HOMEWORK 알림 발송 (실패 격리).
     */
    @Transactional
    public LearningDto.HomeworkCreateResponse createHomework(
            Long academyId, LearningDto.HomeworkCreateRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("숙제 제목은 필수입니다.");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("숙제 내용은 필수입니다.");
        }
        if (req.getClassId() == null && req.getStudentId() == null) {
            throw new IllegalArgumentException("반 또는 학생 지정은 필수입니다.");
        }
        LocalDate dueDate = parseDate(req.getDueDate(), "마감일");

        ReaClass clazz = req.getClassId() != null ? requireClass(academyId, req.getClassId()) : null;
        ReaStudent student = req.getStudentId() != null
                ? requireStudent(academyId, req.getStudentId(), null) : null;

        List<WikiQuestionRef> refs = new ArrayList<>();
        if (req.getQuestionRefs() != null) {
            for (LearningDto.QuestionRefRequest r : req.getQuestionRefs()) {
                if (r.getQuestionId() == null) {
                    throw new IllegalArgumentException("문항 참조의 questionId는 필수입니다.");
                }
                refs.add(WikiQuestionRef.builder()
                        .questionId(r.getQuestionId())
                        .source(r.getSource() == null || r.getSource().isBlank()
                                ? "NAESIN_WIKI" : r.getSource().trim())
                        .build());
            }
        }

        ReaHomework homework = homeworkRepository.save(ReaHomework.builder()
                .academyId(academyId)
                .classId(clazz != null ? clazz.getId() : null)
                .className(clazz != null ? clazz.getName() : null)
                .studentId(student != null ? student.getId() : null)
                .studentName(student != null ? student.getName() : null)
                .title(req.getTitle().trim())
                .content(req.getContent().trim())
                .dueDate(dueDate)
                .questionRefs(refs)
                .build());

        // 대상 학부모에게 HOMEWORK 알림 발송 (실패 격리)
        LearningDto.HomeworkCreateResponse.HomeworkCreateResponseBuilder resp =
                LearningDto.HomeworkCreateResponse.builder()
                        .homework(toHomeworkResponse(homework, null));
        try {
            List<ReaStudent> recipients = resolveHomeworkRecipients(academyId, homework);
            String title = "[숙제] " + homework.getTitle();
            String content = truncate(buildHomeworkMessage(homework), NOTIFICATION_CONTENT_MAX);
            int sent = 0, failed = 0, skipped = 0;
            for (ReaStudent recipient : recipients) {
                NotificationDto.SendResponse sendResp = notificationService.sendToStudent(
                        academyId, recipient.getId(), NotificationType.HOMEWORK, title, content);
                sent += sendResp.getSentCount();
                failed += sendResp.getFailedCount();
                skipped += sendResp.getSkippedCount();
            }
            resp.notificationRequestedCount(recipients.size())
                    .notificationSentCount(sent)
                    .notificationFailedCount(failed)
                    .notificationSkippedCount(skipped);
        } catch (RuntimeException e) {
            resp.notificationError("알림 발송 중 오류: " + e.getMessage());
        }
        return resp.build();
    }

    /**
     * 숙제 목록.
     * - classId 지정: 해당 반의 반별 숙제 (강사 관점)
     * - studentId(또는 authKey) 지정: 해당 학생의 개별 숙제 + 소속 반의 반별 숙제 (학부모 안내용).
     *   각 숙제에 해당 학생의 검사 상태 포함.
     * - 둘 다 없으면: 학원 전체 숙제 (검사 상태 없음)
     */
    public List<LearningDto.HomeworkResponse> listHomework(
            Long academyId, Long classId, Long studentId, String authKey) {
        ReaStudent student = null;
        if (studentId != null || (authKey != null && !authKey.isBlank())) {
            student = requireStudent(academyId, studentId, authKey);
        }
        if (student != null) {
            return listHomeworkForStudent(academyId, student);
        }
        if (classId != null) {
            requireClass(academyId, classId);
            return homeworkRepository
                    .findByAcademyIdAndClassIdOrderByDueDateAscIdAsc(academyId, classId)
                    .stream().map(h -> toHomeworkResponse(h, null)).collect(Collectors.toList());
        }
        return homeworkRepository.findByAcademyIdOrderByDueDateAscIdAsc(academyId)
                .stream().map(h -> toHomeworkResponse(h, null)).collect(Collectors.toList());
    }

    /** 학생 관점 숙제 목록: 개별 숙제 + 소속 반 숙제 (중복 제거, 마감일 순) */
    private List<LearningDto.HomeworkResponse> listHomeworkForStudent(
            Long academyId, ReaStudent student) {
        Map<Long, ReaHomework> merged = new LinkedHashMap<>();
        for (ReaHomework h : homeworkRepository
                .findByAcademyIdAndStudentIdOrderByDueDateAscIdAsc(academyId, student.getId())) {
            merged.put(h.getId(), h);
        }
        List<Long> classIds = studentClassIds(academyId, student.getId());
        if (!classIds.isEmpty()) {
            for (ReaHomework h : homeworkRepository
                    .findByAcademyIdAndClassIdInOrderByDueDateAscIdAsc(academyId, classIds)) {
                merged.putIfAbsent(h.getId(), h);
            }
        }
        Map<Long, ReaHomeworkCheck> checks = homeworkCheckRepository
                .findByAcademyIdAndStudentId(academyId, student.getId()).stream()
                .collect(Collectors.toMap(ReaHomeworkCheck::getHomeworkId, c -> c,
                        (a, b) -> a));
        return merged.values().stream()
                .sorted(Comparator.comparing(ReaHomework::getDueDate)
                        .thenComparing(ReaHomework::getId))
                .map(h -> toHomeworkResponse(h, checks.get(h.getId())))
                .collect(Collectors.toList());
    }

    // ===== 숙제 검사 =====

    /**
     * 숙제 검사 (강사). (homeworkId, studentId)당 1행 — 재검사 시 갱신.
     */
    @Transactional
    public LearningDto.HomeworkCheckResponse checkHomework(
            Long academyId, Long homeworkId, LearningDto.HomeworkCheckRequest req) {
        ReaHomework homework = homeworkRepository.findById(homeworkId)
                .filter(h -> h.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("숙제를 찾을 수 없습니다."));
        ReaStudent student = requireStudent(academyId, req.getStudentId(), null);
        HomeworkCheckStatus status = HomeworkCheckStatus.parse(req.getStatus());
        if (req.getScore() != null && req.getScore() < 0) {
            throw new IllegalArgumentException("검사 점수는 0 이상이어야 합니다.");
        }

        ReaHomeworkCheck check = homeworkCheckRepository
                .findByHomeworkIdAndStudentId(homework.getId(), student.getId())
                .orElseGet(() -> homeworkCheckRepository.save(ReaHomeworkCheck.builder()
                        .academyId(academyId)
                        .homeworkId(homework.getId())
                        .studentId(student.getId())
                        .studentName(student.getName())
                        .status(status)
                        .build()));
        // 신규 생성 시에도 요청값 반영 (builder에 score/comment이 없을 수 있음)
        check.recheck(status, req.getScore(),
                req.getComment() == null ? null : req.getComment().trim());

        return toHomeworkCheckResponse(check, homework.getTitle());
    }

    /** 숙제별 검사 결과 목록 (강사, HW-02) */
    public List<LearningDto.HomeworkCheckResponse> listHomeworkChecks(
            Long academyId, Long homeworkId) {
        ReaHomework homework = homeworkRepository.findById(homeworkId)
                .filter(h -> h.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("숙제를 찾을 수 없습니다."));
        return homeworkCheckRepository.findByAcademyIdAndHomeworkId(academyId, homework.getId())
                .stream()
                .map(c -> toHomeworkCheckResponse(c, homework.getTitle()))
                .collect(Collectors.toList());
    }

    // ===== 진도 =====

    /**
     * 학습 진도 기록 (강사).
     * unit(단원) 필수, progressRate 0~100 필수.
     */
    @Transactional
    public LearningDto.ProgressResponse recordProgress(
            Long academyId, LearningDto.ProgressRecordRequest req) {
        ReaStudent student = requireStudent(academyId, req.getStudentId(), null);
        if (req.getUnit() == null || req.getUnit().isBlank()) {
            throw new IllegalArgumentException("단원은 필수입니다.");
        }
        if (req.getProgressRate() == null || req.getProgressRate() < 0 || req.getProgressRate() > 100) {
            throw new IllegalArgumentException("진도율은 0~100 사이여야 합니다.");
        }
        LocalDate recordedDate = req.getRecordedDate() == null || req.getRecordedDate().isBlank()
                ? ReaTime.today()
                : parseDate(req.getRecordedDate(), "기록 일자");

        ReaProgress progress = progressRepository.save(ReaProgress.builder()
                .academyId(academyId)
                .studentId(student.getId())
                .studentName(student.getName())
                .unit(req.getUnit().trim())
                .progressRate(req.getProgressRate())
                .memo(req.getMemo() == null || req.getMemo().isBlank() ? null : req.getMemo().trim())
                .recordedDate(recordedDate)
                .build());
        return toProgressResponse(progress);
    }

    /** 학생별 진도 조회 — 기록일자 최신순 */
    public List<LearningDto.ProgressResponse> getProgress(
            Long academyId, Long studentId, String authKey) {
        ReaStudent student = requireStudent(academyId, studentId, authKey);
        return progressRepository
                .findByAcademyIdAndStudentIdOrderByRecordedDateDescIdDesc(academyId, student.getId())
                .stream().map(this::toProgressResponse).collect(Collectors.toList());
    }

    // ===== 성적 =====

    /**
     * 성적 등록 (강사). 등록 후 해당 학부모에게 GRADE 알림 발송 (실패 격리).
     * 검증: 0 <= score <= fullScore, fullScore >= 1.
     */
    @Transactional
    public LearningDto.GradeCreateResponse createGrade(
            Long academyId, LearningDto.GradeCreateRequest req) {
        ReaStudent student = requireStudent(academyId, req.getStudentId(), null);
        if (req.getExamName() == null || req.getExamName().isBlank()) {
            throw new IllegalArgumentException("시험명은 필수입니다.");
        }
        if (req.getSubject() == null || req.getSubject().isBlank()) {
            throw new IllegalArgumentException("과목은 필수입니다.");
        }
        if (req.getFullScore() == null || req.getFullScore() < 1) {
            throw new IllegalArgumentException("만점은 1 이상이어야 합니다.");
        }
        if (req.getScore() == null || req.getScore() < 0 || req.getScore() > req.getFullScore()) {
            throw new IllegalArgumentException("점수는 0 이상 만점 이하여야 합니다.");
        }
        LocalDate examDate = req.getExamDate() == null || req.getExamDate().isBlank()
                ? ReaTime.today()
                : parseDate(req.getExamDate(), "시험 일자");

        ReaGrade grade = gradeRepository.save(ReaGrade.builder()
                .academyId(academyId)
                .studentId(student.getId())
                .studentName(student.getName())
                .examName(req.getExamName().trim())
                .subject(req.getSubject().trim())
                .score(req.getScore())
                .fullScore(req.getFullScore())
                .examDate(examDate)
                .note(req.getNote() == null || req.getNote().isBlank() ? null : req.getNote().trim())
                .build());

        LearningDto.GradeCreateResponse.GradeCreateResponseBuilder resp =
                LearningDto.GradeCreateResponse.builder()
                        .grade(toGradeResponse(grade));
        try {
            String title = "[성적] " + grade.getExamName() + " " + grade.getSubject();
            String content = truncate(
                    student.getName() + " 학생의 " + grade.getExamName() + " " + grade.getSubject()
                            + " 성적: " + grade.getScore() + "점 / " + grade.getFullScore() + "점"
                            + " (" + grade.rate() + "점)",
                    NOTIFICATION_CONTENT_MAX);
            NotificationDto.SendResponse sendResp = notificationService.sendToStudent(
                    academyId, student.getId(), NotificationType.GRADE, title, content);
            resp.notificationSentCount(sendResp.getSentCount())
                    .notificationFailedCount(sendResp.getFailedCount())
                    .notificationSkippedCount(sendResp.getSkippedCount());
        } catch (RuntimeException e) {
            resp.notificationError("알림 발송 중 오류: " + e.getMessage());
        }
        return resp.build();
    }

    /** 학생별 성적 이력 — 시험일자 최신순 */
    public List<LearningDto.GradeResponse> getGrades(
            Long academyId, Long studentId, String authKey) {
        ReaStudent student = requireStudent(academyId, studentId, authKey);
        return gradeRepository
                .findByAcademyIdAndStudentIdOrderByExamDateDescIdDesc(academyId, student.getId())
                .stream().map(this::toGradeResponse).collect(Collectors.toList());
    }

    // ===== 학습 리포트 =====

    /**
     * 학습 리포트 데이터 집계 (RPT-01).
     * - 성적: 건수·전체 평균 백분율·과목별 평균·최근 시험명
     * - 숙제: 배정(반별+개별)·검사·완료·미완료·미검사
     * - 진도: 기록 건수·최근 5건
     * - 출결: 최근 30일 등원/결석/지각/조퇴 일수
     */
    public LearningDto.ReportResponse getReport(
            Long academyId, Long studentId, String authKey) {
        ReaStudent student = requireStudent(academyId, studentId, authKey);

        List<ReaGrade> grades = gradeRepository
                .findByAcademyIdAndStudentIdOrderByExamDateDescIdDesc(academyId, student.getId());
        List<ReaHomework> homeworks = new ArrayList<>(
                homeworkRepository.findByAcademyIdAndStudentIdOrderByDueDateAscIdAsc(
                        academyId, student.getId()));
        List<Long> classIds = studentClassIds(academyId, student.getId());
        if (!classIds.isEmpty()) {
            Set<Long> seen = homeworks.stream().map(ReaHomework::getId).collect(Collectors.toSet());
            homeworkRepository.findByAcademyIdAndClassIdInOrderByDueDateAscIdAsc(academyId, classIds)
                    .stream().filter(h -> !seen.contains(h.getId())).forEach(homeworks::add);
        }
        Map<Long, ReaHomeworkCheck> checks = homeworkCheckRepository
                .findByAcademyIdAndStudentId(academyId, student.getId()).stream()
                .collect(Collectors.toMap(ReaHomeworkCheck::getHomeworkId, c -> c, (a, b) -> a));
        List<ReaProgress> progresses = progressRepository
                .findByAcademyIdAndStudentIdOrderByRecordedDateDescIdDesc(academyId, student.getId());

        return LearningDto.ReportResponse.builder()
                .academyId(academyId)
                .studentId(student.getId())
                .studentName(student.getName())
                .gradeSummary(buildGradeSummary(grades))
                .homeworkSummary(buildHomeworkSummary(homeworks, checks))
                .progressSummary(LearningDto.ProgressSummary.builder()
                        .recordCount(progresses.size())
                        .latest(progresses.stream().limit(5)
                                .map(this::toProgressResponse).collect(Collectors.toList()))
                        .build())
                .attendanceSummary(buildAttendanceSummary(academyId, student.getId()))
                .generatedAt(ReaTime.now())
                .build();
    }

    // ===== 내부 집계 =====

    private LearningDto.GradeSummary buildGradeSummary(List<ReaGrade> grades) {
        Double avgRate = grades.isEmpty() ? null
                : Math.round(grades.stream().mapToDouble(ReaGrade::rate).average().orElse(0.0) * 10) / 10.0;
        Map<String, List<ReaGrade>> bySubject = grades.stream()
                .collect(Collectors.groupingBy(ReaGrade::getSubject,
                        LinkedHashMap::new, Collectors.toList()));
        List<LearningDto.SubjectAverage> subjectAverages = bySubject.entrySet().stream()
                .map(e -> LearningDto.SubjectAverage.builder()
                        .subject(e.getKey())
                        .count(e.getValue().size())
                        .avgRate(Math.round(e.getValue().stream()
                                .mapToDouble(ReaGrade::rate).average().orElse(0.0) * 10) / 10.0)
                        .build())
                .collect(Collectors.toList());
        return LearningDto.GradeSummary.builder()
                .gradeCount(grades.size())
                .avgRate(avgRate)
                .subjectAverages(subjectAverages)
                .latestExamName(grades.isEmpty() ? null : grades.get(0).getExamName())
                .build();
    }

    private LearningDto.HomeworkSummary buildHomeworkSummary(
            List<ReaHomework> homeworks, Map<Long, ReaHomeworkCheck> checks) {
        long completed = 0, incomplete = 0;
        for (ReaHomework h : homeworks) {
            ReaHomeworkCheck c = checks.get(h.getId());
            if (c != null) {
                if (c.getStatus() == HomeworkCheckStatus.COMPLETED) completed++;
                else incomplete++;
            }
        }
        long checked = completed + incomplete;
        return LearningDto.HomeworkSummary.builder()
                .assignedCount(homeworks.size())
                .checkedCount(checked)
                .completedCount(completed)
                .incompleteCount(incomplete)
                .uncheckedCount(homeworks.size() - checked)
                .build();
    }

    private LearningDto.AttendanceSummary buildAttendanceSummary(Long academyId, Long studentId) {
        int periodDays = 30;
        LocalDateTime to = ReaTime.now();
        LocalDateTime from = ReaTime.today().minusDays(periodDays - 1).atStartOfDay();
        List<ReaAttendanceEvent> events = attendanceEventRepository
                .findByAcademyIdAndStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                        academyId, studentId, from, to);
        Map<AttendanceEventType, Set<LocalDate>> daysByType = new EnumMap<>(AttendanceEventType.class);
        for (ReaAttendanceEvent e : events) {
            daysByType.computeIfAbsent(e.getEventType(), k -> new HashSet<>())
                    .add(e.getOccurredAt().toLocalDate());
        }
        return LearningDto.AttendanceSummary.builder()
                .periodDays(periodDays)
                .checkInDays(daysByType.getOrDefault(AttendanceEventType.CHECK_IN, Set.of()).size())
                .absentDays(daysByType.getOrDefault(AttendanceEventType.ABSENT, Set.of()).size())
                .lateDays(daysByType.getOrDefault(AttendanceEventType.LATE, Set.of()).size())
                .earlyLeaveDays(daysByType.getOrDefault(AttendanceEventType.EARLY_LEAVE, Set.of()).size())
                .build();
    }

    // ===== 내부 조회 =====

    /** 숙제 발송 대상: 반별이면 반 소속 활성 학생, 학생별이면 해당 학생 1명 */
    private List<ReaStudent> resolveHomeworkRecipients(Long academyId, ReaHomework homework) {
        if (homework.getClassId() != null) {
            List<ReaStudent> recipients = new ArrayList<>();
            for (ClassEnrollment e : enrollmentRepository
                    .findByClassIdAndActiveTrue(homework.getClassId())) {
                studentRepository.findById(e.getStudentId())
                        .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                        .ifPresent(recipients::add);
            }
            return recipients;
        }
        return List.of(requireStudent(academyId, homework.getStudentId(), null));
    }

    private String buildHomeworkMessage(ReaHomework homework) {
        StringBuilder sb = new StringBuilder();
        if (homework.getStudentName() != null) {
            sb.append(homework.getStudentName()).append(" 학생");
        } else if (homework.getClassName() != null) {
            sb.append(homework.getClassName());
        }
        sb.append(" 숙제 안내 (마감: ").append(homework.getDueDate().format(DATE_FORMAT)).append(")\n");
        sb.append(homework.getContent());
        return sb.toString();
    }

    /** 학생의 소속 반 ID 목록 (같은 학원의 활성 반만) */
    private List<Long> studentClassIds(Long academyId, Long studentId) {
        List<Long> classIds = new ArrayList<>();
        for (ClassEnrollment e : enrollmentRepository.findByStudentIdAndActiveTrue(studentId)) {
            classRepository.findById(e.getClassId())
                    .filter(c -> c.getAcademyId().equals(academyId))
                    .ifPresent(c -> classIds.add(c.getId()));
        }
        return classIds;
    }

    /**
     * 학생 조회: studentId 또는 authKey 중 하나로 같은 학원의 활성 학생을 찾는다.
     * (NotificationService.requireStudent와 동일한 격리 규칙)
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

    private ReaClass requireClass(Long academyId, Long classId) {
        return classRepository.findById(classId)
                .filter(c -> c.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("반을 찾을 수 없습니다."));
    }

    private LocalDate parseDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은(는) 필수입니다. (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value.trim(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    fieldName + " 형식이 올바르지 않습니다. (yyyy-MM-dd): " + value);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ===== DTO 변환 =====

    private LearningDto.HomeworkResponse toHomeworkResponse(
            ReaHomework h, ReaHomeworkCheck check) {
        LearningDto.HomeworkResponse.HomeworkResponseBuilder b =
                LearningDto.HomeworkResponse.builder()
                        .id(h.getId())
                        .academyId(h.getAcademyId())
                        .classId(h.getClassId())
                        .className(h.getClassName())
                        .studentId(h.getStudentId())
                        .studentName(h.getStudentName())
                        .title(h.getTitle())
                        .content(h.getContent())
                        .dueDate(h.getDueDate().format(DATE_FORMAT))
                        .questionRefCount(h.getQuestionRefs() == null ? 0 : h.getQuestionRefs().size())
                        .createdAt(h.getCreatedAt());
        if (check != null) {
            b.checkStatus(check.getStatus().name())
                    .checkStatusLabel(check.getStatus().getLabel())
                    .checkScore(check.getScore())
                    .checkComment(check.getComment())
                    .checkedAt(check.getCheckedAt());
        }
        return b.build();
    }

    private LearningDto.HomeworkCheckResponse toHomeworkCheckResponse(
            ReaHomeworkCheck c, String homeworkTitle) {
        return LearningDto.HomeworkCheckResponse.builder()
                .id(c.getId())
                .homeworkId(c.getHomeworkId())
                .homeworkTitle(homeworkTitle)
                .studentId(c.getStudentId())
                .studentName(c.getStudentName())
                .status(c.getStatus().name())
                .statusLabel(c.getStatus().getLabel())
                .score(c.getScore())
                .comment(c.getComment())
                .checkedAt(c.getCheckedAt())
                .build();
    }

    private LearningDto.ProgressResponse toProgressResponse(ReaProgress p) {
        return LearningDto.ProgressResponse.builder()
                .id(p.getId())
                .studentId(p.getStudentId())
                .studentName(p.getStudentName())
                .unit(p.getUnit())
                .progressRate(p.getProgressRate())
                .memo(p.getMemo())
                .recordedDate(p.getRecordedDate())
                .createdAt(p.getCreatedAt())
                .build();
    }

    private LearningDto.GradeResponse toGradeResponse(ReaGrade g) {
        return LearningDto.GradeResponse.builder()
                .id(g.getId())
                .studentId(g.getStudentId())
                .studentName(g.getStudentName())
                .examName(g.getExamName())
                .subject(g.getSubject())
                .score(g.getScore())
                .fullScore(g.getFullScore())
                .rate(g.rate())
                .examDate(g.getExamDate())
                .note(g.getNote())
                .createdAt(g.getCreatedAt())
                .build();
    }
}
