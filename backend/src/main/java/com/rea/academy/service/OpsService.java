package com.rea.academy.service;

import com.rea.academy.common.ReaTime;
import com.rea.academy.domain.attendance.AttendanceEventType;
import com.rea.academy.domain.attendance.ReaAttendanceEvent;
import com.rea.academy.domain.clazz.ClassEnrollmentRepository;
import com.rea.academy.domain.clazz.ReaClass;
import com.rea.academy.domain.clazz.ReaClassRepository;
import com.rea.academy.domain.consultation.ConsultationStatus;
import com.rea.academy.domain.consultation.ReaConsultation;
import com.rea.academy.domain.consultation.ReaConsultationRepository;
import com.rea.academy.domain.learning.ReaGrade;
import com.rea.academy.domain.learning.ReaGradeRepository;
import com.rea.academy.domain.learning.ReaHomework;
import com.rea.academy.domain.learning.ReaHomeworkRepository;
import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.domain.notice.ReaNotice;
import com.rea.academy.domain.notice.ReaNoticeRepository;
import com.rea.academy.domain.ops.OpsAttendanceEventRepository;
import com.rea.academy.domain.ops.ReaSchedule;
import com.rea.academy.domain.ops.ReaScheduleRepository;
import com.rea.academy.domain.ops.ReaTeacherAssignment;
import com.rea.academy.domain.ops.ReaTeacherAssignmentRepository;
import com.rea.academy.domain.ops.ScheduleTargetType;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.OpsDto;
import com.rea.academy.dto.StudentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 운영 관리 서비스 (7단계).
 * - 일정·캘린더: 학원/반별 일정 등록·월별 조회·수정·삭제, 상담 스레드 연결(상담 일정)
 * - 강사 담당: 담당 반(기존 반 teacherId) + 담당 업무 메모 조회·수정
 * - 통합 검색: 학생 이름으로 학생·출결·성적·숙제·상담·공지 횡단 검색
 * - 통계 대시보드: 학생/반 수, 오늘 출결 현황, 미답변 문의 수, 이번 달 공지 수
 * - CSV 가져오기·내보내기: 학생 목록/일괄 등록, 출결 기록 (엑셀 호환)
 *
 * 실제 외부 메시지 발송 없음. 수납·결제 기능 없음.
 * 격리 규칙: 모든 조회·수정은 academyId로 스코핑. 타 학원 데이터 접근 불가.
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpsService {

    private final ReaScheduleRepository scheduleRepository;
    private final ReaTeacherAssignmentRepository teacherAssignmentRepository;
    private final OpsAttendanceEventRepository attendanceEventRepository;
    private final ReaStudentRepository studentRepository;
    private final ReaMemberRepository memberRepository;
    private final ReaClassRepository classRepository;
    private final ClassEnrollmentRepository enrollmentRepository;
    private final ReaConsultationRepository consultationRepository;
    private final ReaNoticeRepository noticeRepository;
    private final ReaGradeRepository gradeRepository;
    private final ReaHomeworkRepository homeworkRepository;
    private final MemberService memberService;
    private final ClassService classService;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter YEAR_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int SEARCH_LIMIT = 20;

    // ================= 일정·캘린더 =================

    /**
     * 일정 등록.
     * - title(제목)·date(yyyy-MM-dd) 필수
     * - targetType=CLASS이면 classId 필수 (해당 학원 반)
     * - consultationId가 있으면 해당 학원 상담 스레드여야 함 (상담 일정 연결)
     */
    @Transactional
    public OpsDto.ScheduleResponse createSchedule(
            Long academyId, OpsDto.ScheduleCreateRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("일정 제목은 필수입니다.");
        }
        LocalDate date = parseDate(req.getDate(), "일정 일자");
        LocalTime startTime = parseTimeOrNull(req.getStartTime(), "시작 시각");
        LocalTime endTime = parseTimeOrNull(req.getEndTime(), "종료 시각");
        validateTimeRange(startTime, endTime);
        ScheduleTargetType targetType = ScheduleTargetType.parse(req.getTargetType());

        ReaClass clazz = null;
        if (targetType == ScheduleTargetType.CLASS) {
            if (req.getClassId() == null) {
                throw new IllegalArgumentException("반별 일정은 대상 반 지정이 필수입니다.");
            }
            clazz = requireClass(academyId, req.getClassId());
        }

        ReaConsultation consultation = null;
        if (req.getConsultationId() != null) {
            consultation = requireConsultation(academyId, req.getConsultationId());
        }

        ReaSchedule schedule = scheduleRepository.save(ReaSchedule.builder()
                .academyId(academyId)
                .title(req.getTitle().trim())
                .date(date)
                .startTime(startTime)
                .endTime(endTime)
                .targetType(targetType)
                .classId(clazz != null ? clazz.getId() : null)
                .className(clazz != null ? clazz.getName() : null)
                .consultationId(consultation != null ? consultation.getId() : null)
                .consultationTitle(consultation != null ? consultation.getTitle() : null)
                .memo(blankToNull(req.getMemo()))
                .build());
        return toScheduleResponse(schedule);
    }

    /**
     * 월별 일정 조회 (캘린더용). yearMonth=yyyy-MM (필수).
     * 일자·시작시각 순 정렬.
     */
    public List<OpsDto.ScheduleResponse> listSchedules(Long academyId, String yearMonth) {
        YearMonth ym = parseYearMonth(yearMonth);
        return scheduleRepository
                .findByAcademyIdAndDateBetweenOrderByDateAscStartTimeAscIdAsc(
                        academyId, ym.atDay(1), ym.atEndOfMonth())
                .stream()
                .map(this::toScheduleResponse)
                .collect(Collectors.toList());
    }

    /** 일정 수정 (PUT). null인 항목은 변경하지 않는다. */
    @Transactional
    public OpsDto.ScheduleResponse updateSchedule(
            Long academyId, Long scheduleId, OpsDto.ScheduleUpdateRequest req) {
        ReaSchedule schedule = requireSchedule(academyId, scheduleId);

        String title = null;
        if (req.getTitle() != null) {
            if (req.getTitle().isBlank()) {
                throw new IllegalArgumentException("일정 제목은 비워 둘 수 없습니다.");
            }
            title = req.getTitle().trim();
        }
        LocalDate date = req.getDate() != null ? parseDate(req.getDate(), "일정 일자") : null;
        boolean startSet = req.getStartTime() != null;
        LocalTime startTime = startSet ? parseTimeOrNull(req.getStartTime(), "시작 시각") : null;
        boolean endSet = req.getEndTime() != null;
        LocalTime endTime = endSet ? parseTimeOrNull(req.getEndTime(), "종료 시각") : null;
        validateTimeRange(
                startSet ? startTime : schedule.getStartTime(),
                endSet ? endTime : schedule.getEndTime());

        ScheduleTargetType targetType = req.getTargetType() != null
                ? ScheduleTargetType.parse(req.getTargetType()) : null;
        ScheduleTargetType effectiveTarget = targetType != null ? targetType : schedule.getTargetType();

        Long classId = null;
        String className = null;
        if (effectiveTarget == ScheduleTargetType.CLASS) {
            Long reqClassId = req.getClassId() != null ? req.getClassId() : schedule.getClassId();
            if (reqClassId == null) {
                throw new IllegalArgumentException("반별 일정은 대상 반 지정이 필수입니다.");
            }
            ReaClass clazz = requireClass(academyId, reqClassId);
            classId = clazz.getId();
            className = clazz.getName();
        } else if (targetType == ScheduleTargetType.ALL) {
            classId = null;
            className = null;
        }

        boolean consultationSet = req.getConsultationId() != null;
        Long consultationId = null;
        String consultationTitle = null;
        if (consultationSet) {
            ReaConsultation consultation = requireConsultation(academyId, req.getConsultationId());
            consultationId = consultation.getId();
            consultationTitle = consultation.getTitle();
        }

        schedule.update(title, date, startTime, startSet, endTime, endSet,
                targetType, classId, className,
                consultationId, consultationSet, consultationTitle,
                blankToNull(req.getMemo()), req.getMemo() != null);
        return toScheduleResponse(schedule);
    }

    /** 일정 삭제. */
    @Transactional
    public void deleteSchedule(Long academyId, Long scheduleId) {
        ReaSchedule schedule = requireSchedule(academyId, scheduleId);
        scheduleRepository.delete(schedule);
    }

    // ================= 강사 담당 =================

    /**
     * 강사 담당 현황: 담당 반(기존 반 teacherId 기준) + 담당 업무 메모.
     * 메모가 없으면 dutyMemo는 null.
     */
    public OpsDto.TeacherAssignmentResponse getTeacherAssignments(
            Long academyId, Long teacherId) {
        ReaMember teacher = requireMember(academyId, teacherId);

        List<OpsDto.TeacherClassSummary> classes = classRepository
                .findByAcademyIdAndActiveTrueOrderByIdAsc(academyId).stream()
                .filter(c -> teacherId.equals(c.getTeacherId()))
                .map(c -> OpsDto.TeacherClassSummary.builder()
                        .classId(c.getId())
                        .className(c.getName())
                        .studentCount(enrollmentRepository.countByClassIdAndActiveTrue(c.getId()))
                        .build())
                .collect(Collectors.toList());
        long totalStudents = classes.stream()
                .mapToLong(OpsDto.TeacherClassSummary::getStudentCount).sum();

        String dutyMemo = teacherAssignmentRepository
                .findByAcademyIdAndTeacherId(academyId, teacherId)
                .map(ReaTeacherAssignment::getDutyMemo)
                .orElse(null);

        return OpsDto.TeacherAssignmentResponse.builder()
                .teacherId(teacher.getId())
                .teacherName(teacher.getName())
                .dutyMemo(dutyMemo)
                .classes(classes)
                .classCount(classes.size())
                .totalStudentCount(totalStudents)
                .build();
    }

    /**
     * 담당 업무 메모 수정 (upsert). dutyMemo가 null/빈 값이면 메모 삭제(행 유지).
     */
    @Transactional
    public OpsDto.TeacherAssignmentResponse updateDutyMemo(
            Long academyId, Long teacherId, String dutyMemo) {
        requireMember(academyId, teacherId);
        ReaTeacherAssignment assignment = teacherAssignmentRepository
                .findByAcademyIdAndTeacherId(academyId, teacherId)
                .orElseGet(() -> teacherAssignmentRepository.save(ReaTeacherAssignment.builder()
                        .academyId(academyId)
                        .teacherId(teacherId)
                        .build()));
        assignment.updateDutyMemo(blankToNull(dutyMemo));
        return getTeacherAssignments(academyId, teacherId);
    }

    // ================= 통합 검색 =================

    /**
     * 통합 검색: 학생 이름으로 학생·출결·성적·숙제·상담·공지를 횡단 검색.
     * 섹션별 최대 20건.
     */
    public OpsDto.SearchResponse search(Long academyId, String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("검색어는 필수입니다.");
        }
        String q = query.trim();

        List<ReaStudent> students = studentRepository
                .findByAcademyIdAndNameContainingAndActiveTrue(academyId, q);
        List<Long> studentIds = students.stream().map(ReaStudent::getId).collect(Collectors.toList());

        List<OpsDto.StudentHit> studentHits = students.stream()
                .limit(SEARCH_LIMIT)
                .map(s -> OpsDto.StudentHit.builder()
                        .studentId(s.getId())
                        .studentName(s.getName())
                        .grade(s.getGrade())
                        .parentName(s.getParentName())
                        .parentPhone(s.getParentPhone())
                        .build())
                .collect(Collectors.toList());

        List<OpsDto.AttendanceHit> attendanceHits = new ArrayList<>();
        List<OpsDto.GradeHit> gradeHits = new ArrayList<>();
        List<OpsDto.HomeworkHit> homeworkHits = new ArrayList<>();
        List<OpsDto.ConsultationHit> consultationHits = new ArrayList<>();
        for (ReaStudent student : students) {
            if (attendanceHits.size() < SEARCH_LIMIT) {
                attendanceEventRepository
                        .findByAcademyIdAndStudentIdOrderByOccurredAtDesc(
                                academyId, student.getId()).stream()
                        .limit(3)
                        .forEach(e -> {
                            if (attendanceHits.size() < SEARCH_LIMIT) {
                                attendanceHits.add(OpsDto.AttendanceHit.builder()
                                        .id(e.getId())
                                        .studentId(student.getId())
                                        .studentName(student.getName())
                                        .eventType(e.getEventType().name())
                                        .eventTypeLabel(eventTypeLabel(e.getEventType()))
                                        .occurredAt(e.getOccurredAt())
                                        .build());
                            }
                        });
            }
            if (gradeHits.size() < SEARCH_LIMIT) {
                gradeRepository
                        .findByAcademyIdAndStudentIdOrderByExamDateDescIdDesc(
                                academyId, student.getId()).stream()
                        .limit(3)
                        .forEach(g -> {
                            if (gradeHits.size() < SEARCH_LIMIT) {
                                gradeHits.add(OpsDto.GradeHit.builder()
                                        .id(g.getId())
                                        .studentId(student.getId())
                                        .studentName(student.getName())
                                        .examName(g.getExamName())
                                        .subject(g.getSubject())
                                        .score(g.getScore())
                                        .fullScore(g.getFullScore())
                                        .examDate(g.getExamDate())
                                        .build());
                            }
                        });
            }
            if (homeworkHits.size() < SEARCH_LIMIT) {
                homeworkRepository
                        .findByAcademyIdAndStudentIdOrderByDueDateAscIdAsc(
                                academyId, student.getId()).stream()
                        .limit(3)
                        .forEach(h -> {
                            if (homeworkHits.size() < SEARCH_LIMIT) {
                                homeworkHits.add(OpsDto.HomeworkHit.builder()
                                        .id(h.getId())
                                        .title(h.getTitle())
                                        .className(h.getClassName())
                                        .studentName(student.getName())
                                        .dueDate(h.getDueDate())
                                        .build());
                            }
                        });
            }
            if (consultationHits.size() < SEARCH_LIMIT) {
                consultationRepository
                        .findByAcademyIdAndStudentIdAndActiveTrueOrderByIdDesc(
                                academyId, student.getId()).stream()
                        .limit(3)
                        .forEach(c -> {
                            if (consultationHits.size() < SEARCH_LIMIT) {
                                consultationHits.add(toConsultationHit(c));
                            }
                        });
            }
        }

        // 학생 이름으로 찾지 못한 상담 중 제목에 검색어가 들어간 것도 포함
        consultationRepository.findByAcademyIdAndActiveTrueOrderByIdDesc(academyId).stream()
                .filter(c -> c.getTitle() != null && c.getTitle().contains(q))
                .filter(c -> !studentIds.contains(c.getStudentId()))
                .limit(SEARCH_LIMIT)
                .forEach(c -> {
                    if (consultationHits.size() < SEARCH_LIMIT) {
                        consultationHits.add(toConsultationHit(c));
                    }
                });

        List<OpsDto.NoticeHit> noticeHits = noticeRepository
                .findByAcademyIdAndActiveTrueOrderByIdDesc(academyId).stream()
                .filter(n -> n.getTitle() != null && n.getTitle().contains(q))
                .limit(SEARCH_LIMIT)
                .map(n -> OpsDto.NoticeHit.builder()
                        .id(n.getId())
                        .title(n.getTitle())
                        .scope(n.getClassId() == null ? "전체공지" : "반소식")
                        .createdAt(n.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return OpsDto.SearchResponse.builder()
                .query(q)
                .students(studentHits)
                .attendances(attendanceHits)
                .grades(gradeHits)
                .homework(homeworkHits)
                .consultations(consultationHits)
                .notices(noticeHits)
                .build();
    }

    // ================= 통계 대시보드 =================

    /**
     * 통계 대시보드 (OPS-02):
     * 학원별 활성 학생 수·활성 반 수·오늘 출결 현황·미답변(OPEN) 상담 수·이번 달 공지 수.
     */
    public OpsDto.DashboardStatsResponse getDashboardStats(Long academyId) {
        long studentCount = studentRepository
                .findByAcademyIdAndActiveTrue(academyId).size();
        long classCount = classRepository
                .findByAcademyIdAndActiveTrueOrderByIdAsc(academyId).size();

        LocalDate today = ReaTime.today();
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        List<ReaAttendanceEvent> todayEvents = attendanceEventRepository
                .findByAcademyIdAndOccurredAtBetween(academyId, from, to);
        long distinctStudents = todayEvents.stream()
                .map(ReaAttendanceEvent::getStudentId).distinct().count();
        OpsDto.TodayAttendanceStats todayAttendance = OpsDto.TodayAttendanceStats.builder()
                .date(today)
                .checkInCount(countByType(todayEvents, AttendanceEventType.CHECK_IN))
                .checkOutCount(countByType(todayEvents, AttendanceEventType.CHECK_OUT))
                .absentCount(countByType(todayEvents, AttendanceEventType.ABSENT))
                .lateCount(countByType(todayEvents, AttendanceEventType.LATE))
                .earlyLeaveCount(countByType(todayEvents, AttendanceEventType.EARLY_LEAVE))
                .studentCount(distinctStudents)
                .build();

        long unanswered = consultationRepository
                .findByAcademyIdAndActiveTrueOrderByIdDesc(academyId).stream()
                .filter(c -> c.getStatus() == ConsultationStatus.OPEN)
                .count();

        YearMonth ym = YearMonth.from(ReaTime.today());
        long monthlyNotices = noticeRepository
                .findByAcademyIdAndActiveTrueOrderByIdDesc(academyId).stream()
                .filter(n -> n.getCreatedAt() != null
                        && YearMonth.from(n.getCreatedAt()).equals(ym))
                .count();

        return OpsDto.DashboardStatsResponse.builder()
                .academyId(academyId)
                .studentCount(studentCount)
                .classCount(classCount)
                .todayAttendance(todayAttendance)
                .unansweredConsultationCount(unanswered)
                .monthlyNoticeCount(monthlyNotices)
                .month(ym.format(YEAR_MONTH_FORMAT))
                .generatedAt(ReaTime.now())
                .build();
    }

    // ================= CSV 내보내기 =================

    /**
     * 학생 목록 CSV 내보내기 (엑셀 호환: UTF-8 BOM + CRLF + RFC 4180 인용).
     * 열: 이름,학년,학교,인증키,학부모명,학부모연락처,반,등록일
     */
    public byte[] exportStudentsCsv(Long academyId) {
        List<ReaStudent> students = studentRepository
                .findByAcademyIdAndActiveTrue(academyId);

        Map<Long, String> classNames = classRepository
                .findByAcademyIdAndActiveTrueOrderByIdAsc(academyId).stream()
                .collect(Collectors.toMap(ReaClass::getId, ReaClass::getName,
                        (a, b) -> a, LinkedHashMap::new));

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"이름", "학년", "학교", "인증키", "학부모명", "학부모연락처", "반", "등록일"});
        for (ReaStudent s : students) {
            String classes = enrollmentRepository.findByStudentIdAndActiveTrue(s.getId()).stream()
                    .map(e -> classNames.get(e.getClassId()))
                    .filter(n -> n != null)
                    .collect(Collectors.joining("/"));
            rows.add(new String[]{
                    s.getName(),
                    orEmpty(s.getGrade()),
                    orEmpty(s.getSchoolName()),
                    orEmpty(s.getAuthKey()),
                    orEmpty(s.getParentName()),
                    orEmpty(s.getParentPhone()),
                    classes,
                    s.getCreatedAt() != null ? s.getCreatedAt().toLocalDate().toString() : ""
            });
        }
        return toCsvBytes(rows);
    }

    /**
     * 출결 기록 CSV 내보내기 (반별·월별, 엑셀 호환).
     * 열: 일자,이름,반,구분,발생시각,메모
     * - classId: 선택 (없으면 학원 전체)
     * - yearMonth: 필수 (yyyy-MM)
     */
    public byte[] exportAttendanceCsv(Long academyId, Long classId, String yearMonth) {
        YearMonth ym = parseYearMonth(yearMonth);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.atEndOfMonth().plusDays(1).atStartOfDay();

        List<ReaStudent> students;
        Map<Long, String> classNames = new LinkedHashMap<>();
        if (classId != null) {
            ReaClass clazz = requireClass(academyId, classId);
            classNames.put(clazz.getId(), clazz.getName());
            students = enrollmentRepository.findByClassIdAndActiveTrue(classId).stream()
                    .map(e -> studentRepository.findById(e.getStudentId()).orElse(null))
                    .filter(s -> s != null && academyId.equals(s.getAcademyId())
                            && Boolean.TRUE.equals(s.getActive()))
                    .collect(Collectors.toList());
        } else {
            classRepository.findByAcademyIdAndActiveTrueOrderByIdAsc(academyId)
                    .forEach(c -> classNames.put(c.getId(), c.getName()));
            students = studentRepository.findByAcademyIdAndActiveTrue(academyId);
        }
        Map<Long, ReaStudent> studentMap = students.stream()
                .collect(Collectors.toMap(ReaStudent::getId, s -> s, (a, b) -> a));

        // 반별 조회 시 학생의 반 이름: 해당 반(또는 전체 조회 시 소속 반 목록)
        Map<Long, String> studentClassNames = new LinkedHashMap<>();
        for (ReaStudent s : students) {
            if (classId != null) {
                studentClassNames.put(s.getId(), classNames.get(classId));
            } else {
                String names = enrollmentRepository.findByStudentIdAndActiveTrue(s.getId()).stream()
                        .map(e -> classNames.get(e.getClassId()))
                        .filter(n -> n != null)
                        .collect(Collectors.joining("/"));
                studentClassNames.put(s.getId(), names);
            }
        }

        List<ReaAttendanceEvent> events = attendanceEventRepository
                .findByAcademyIdAndOccurredAtBetween(academyId, from, to).stream()
                .filter(e -> studentMap.containsKey(e.getStudentId()))
                .sorted((a, b) -> {
                    int c = a.getOccurredAt().compareTo(b.getOccurredAt());
                    if (c != 0) return c;
                    return studentMap.get(a.getStudentId()).getName()
                            .compareTo(studentMap.get(b.getStudentId()).getName());
                })
                .collect(Collectors.toList());

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"일자", "이름", "반", "구분", "발생시각", "메모"});
        for (ReaAttendanceEvent e : events) {
            ReaStudent s = studentMap.get(e.getStudentId());
            rows.add(new String[]{
                    e.getOccurredAt().toLocalDate().toString(),
                    s.getName(),
                    orEmpty(studentClassNames.get(s.getId())),
                    eventTypeLabel(e.getEventType()),
                    e.getOccurredAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    orEmpty(e.getNote())
            });
        }
        return toCsvBytes(rows);
    }

    // ================= CSV 가져오기 =================

    /**
     * 학생 일괄 등록 CSV 가져오기.
     * 입력 형식: 헤더(선택) + 행. 열 순서: 이름,학년,반,학부모명,연락처
     * - 이름이 비어 있으면 해당 행 실패
     * - 반 이름이 학원에 없으면 학생은 등록되고 반 배정은 생략 (errors에 경고 기록)
     */
    @Transactional
    public OpsDto.StudentImportResponse importStudentsCsv(Long academyId, String csv) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("CSV 내용이 비어 있습니다.");
        }
        List<String[]> records = parseCsv(csv);

        Map<String, Long> classNameToId = classRepository
                .findByAcademyIdAndActiveTrueOrderByIdAsc(academyId).stream()
                .collect(Collectors.toMap(ReaClass::getName, ReaClass::getId,
                        (a, b) -> a, LinkedHashMap::new));

        int totalRows = 0;
        int successCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();

        int lineNo = 0;
        for (String[] cols : records) {
            lineNo++;
            if (cols.length == 0) continue;
            // 헤더 행 감지: 첫 셀이 "이름"이면 건너뜀
            if (lineNo == 1 && cols[0].trim().equals("이름")) continue;
            // 전부 빈 행은 건너뜀
            boolean allBlank = true;
            for (String c : cols) {
                if (c != null && !c.isBlank()) { allBlank = false; break; }
            }
            if (allBlank) continue;

            totalRows++;
            String name = col(cols, 0).trim();
            String grade = blankToNull(col(cols, 1));
            String className = col(cols, 2).trim();
            String parentName = blankToNull(col(cols, 3));
            String parentPhone = blankToNull(col(cols, 4));

            if (name.isBlank()) {
                failedCount++;
                errors.add(lineNo + "행: 이름이 비어 있습니다.");
                continue;
            }

            StudentDto.DetailResponse created = memberService.createStudent(
                    StudentDto.CreateRequest.builder()
                            .academyId(academyId)
                            .name(name)
                            .grade(grade)
                            .parentName(parentName)
                            .parentPhone(parentPhone)
                            .build());
            successCount++;

            if (!className.isBlank()) {
                Long targetClassId = classNameToId.get(className);
                if (targetClassId == null) {
                    errors.add(lineNo + "행('" + name + "'): 반 '" + className
                            + "'이(가) 없어 배정을 생략했습니다.");
                } else {
                    classService.assignStudents(academyId, targetClassId,
                            List.of(created.getId()));
                }
            }
        }

        return OpsDto.StudentImportResponse.builder()
                .totalRows(totalRows)
                .successCount(successCount)
                .failedCount(failedCount)
                .errors(errors)
                .build();
    }

    // ================= 내부 유틸 =================

    private ReaSchedule requireSchedule(Long academyId, Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .filter(s -> academyId.equals(s.getAcademyId()))
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없습니다: " + scheduleId));
    }

    private ReaClass requireClass(Long academyId, Long classId) {
        return classRepository.findById(classId)
                .filter(c -> academyId.equals(c.getAcademyId()))
                .orElseThrow(() -> new IllegalArgumentException("반을 찾을 수 없습니다: " + classId));
    }

    private ReaMember requireMember(Long academyId, Long memberId) {
        return memberRepository.findById(memberId)
                .filter(m -> academyId.equals(m.getAcademyId())
                        && Boolean.TRUE.equals(m.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다: " + memberId));
    }

    private ReaConsultation requireConsultation(Long academyId, Long consultationId) {
        return consultationRepository.findById(consultationId)
                .filter(c -> academyId.equals(c.getAcademyId()))
                .orElseThrow(() -> new IllegalArgumentException("상담 스레드를 찾을 수 없습니다: " + consultationId));
    }

    private LocalDate parseDate(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "(yyyy-MM-dd)은(는) 필수입니다.");
        }
        try {
            return LocalDate.parse(value.trim(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldName + " 형식이 잘못되었습니다 (yyyy-MM-dd): " + value);
        }
    }

    private LocalTime parseTimeOrNull(String value, String fieldName) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value.trim(), TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldName + " 형식이 잘못되었습니다 (HH:mm): " + value);
        }
    }

    private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("종료 시각은 시작 시각보다 늦어야 합니다.");
        }
    }

    private YearMonth parseYearMonth(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("yearMonth(yyyy-MM)은(는) 필수입니다.");
        }
        try {
            return YearMonth.parse(value.trim(), YEAR_MONTH_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("yearMonth 형식이 잘못되었습니다 (yyyy-MM): " + value);
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private String col(String[] cols, int index) {
        return index < cols.length && cols[index] != null ? cols[index] : "";
    }

    private String eventTypeLabel(AttendanceEventType type) {
        if (type == null) return "";
        return switch (type) {
            case CHECK_IN -> "등원";
            case CHECK_OUT -> "하원";
            case ABSENT -> "결석";
            case LATE -> "지각";
            case EARLY_LEAVE -> "조퇴";
        };
    }

    private long countByType(List<ReaAttendanceEvent> events, AttendanceEventType type) {
        return events.stream().filter(e -> e.getEventType() == type).count();
    }

    private OpsDto.ScheduleResponse toScheduleResponse(ReaSchedule s) {
        return OpsDto.ScheduleResponse.builder()
                .id(s.getId())
                .academyId(s.getAcademyId())
                .title(s.getTitle())
                .date(s.getDate())
                .startTime(s.getStartTime() != null ? s.getStartTime().format(TIME_FORMAT) : null)
                .endTime(s.getEndTime() != null ? s.getEndTime().format(TIME_FORMAT) : null)
                .targetType(s.getTargetType() != null ? s.getTargetType().name() : null)
                .targetTypeLabel(s.getTargetType() != null ? s.getTargetType().label() : null)
                .classId(s.getClassId())
                .className(s.getClassName())
                .consultationId(s.getConsultationId())
                .consultationTitle(s.getConsultationTitle())
                .memo(s.getMemo())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private OpsDto.ConsultationHit toConsultationHit(ReaConsultation c) {
        return OpsDto.ConsultationHit.builder()
                .id(c.getId())
                .studentId(c.getStudentId())
                .studentName(c.getStudentName())
                .type(c.getType().name())
                .typeLabel(c.getType().getLabel())
                .status(c.getStatus().name())
                .statusLabel(c.getStatus().getLabel())
                .title(c.getTitle())
                .build();
    }

    // ================= CSV 유틸 =================

    /**
     * RFC 4180 인용 규칙으로 CSV 바이트 생성 (엑셀 한글 호환: UTF-8 BOM + CRLF).
     */
    private byte[] toCsvBytes(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(escapeCsvField(row[i]));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String escapeCsvField(String value) {
        if (value == null) return "";
        boolean needQuote = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * CSV 파서: 큰따옴표 인용·이스케이프("")·인용부 내 개행 지원.
     * 선행 BOM 제거. 각 레코드는 String[]로 반환.
     */
    private List<String[]> parseCsv(String csv) {
        String text = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        List<String[]> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(ch);
                    i++;
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                    i++;
                } else if (ch == ',') {
                    current.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (ch == '\r') {
                    i++;
                } else if (ch == '\n') {
                    current.add(field.toString());
                    field.setLength(0);
                    records.add(current.toArray(new String[0]));
                    current = new ArrayList<>();
                    i++;
                } else {
                    field.append(ch);
                    i++;
                }
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current.toArray(new String[0]));
        }
        return records;
    }
}
