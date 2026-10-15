package com.rea.academy.service;

import com.rea.academy.domain.clazz.ClassEnrollment;
import com.rea.academy.domain.clazz.ClassEnrollmentRepository;
import com.rea.academy.domain.clazz.ReaClass;
import com.rea.academy.domain.clazz.ReaClassRepository;
import com.rea.academy.domain.member.*;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.StudentDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 반 관리 서비스.
 * - 반 CRUD (생성·수정·종료)
 * - 학생 배정·제외·반 이동
 * - 반별 주간 시간표 조회
 *
 * 격리 규칙: 모든 조회는 academyId로 스코핑. 타 학원 데이터 접근 불가.
 * 권한: 인증 미구현 단계이므로 academyId 스코핑으로 격리.
 *   (원장=전체, 강사=조회+담당반 관리는 인증 도입 시 Controller/Service 경계에서 적용 예정)
 *
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClassService {

    private final ReaClassRepository classRepository;
    private final ClassEnrollmentRepository enrollmentRepository;
    private final ReaMemberRepository memberRepository;
    private final ReaStudentRepository studentRepository;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 3자리 요일 코드 → DayOfWeek 매핑 (java.time.DayOfWeek 상수는 MONDAY~SUNDAY) */
    private static final Map<String, DayOfWeek> DAY_CODES = Map.of(
            "MON", DayOfWeek.MONDAY,
            "TUE", DayOfWeek.TUESDAY,
            "WED", DayOfWeek.WEDNESDAY,
            "THU", DayOfWeek.THURSDAY,
            "FRI", DayOfWeek.FRIDAY,
            "SAT", DayOfWeek.SATURDAY,
            "SUN", DayOfWeek.SUNDAY);

    private static DayOfWeek parseDayCode(String code) {
        DayOfWeek day = DAY_CODES.get(code);
        if (day == null) {
            throw new IllegalArgumentException("잘못된 요일 값입니다: " + code);
        }
        return day;
    }

    // ===== 반 생성·수정·종료 =====

    @Transactional
    public ClassDto.DetailResponse createClass(Long academyId, ClassDto.CreateRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("반 이름은 필수입니다.");
        }
        if (req.getTeacherId() == null) {
            throw new IllegalArgumentException("담당 강사는 필수입니다.");
        }
        ReaMember teacher = requireTeacher(academyId, req.getTeacherId());
        String weekdays = normalizeWeekdays(req.getWeekdays());
        LocalTime startTime = parseTime(req.getStartTime());
        LocalTime endTime = parseTime(req.getEndTime());
        validateTimeRange(startTime, endTime);
        validateCapacity(req.getCapacity());

        ReaClass clazz = ReaClass.builder()
                .academyId(academyId)
                .name(req.getName().trim())
                .description(req.getDescription())
                .teacherId(teacher.getId())
                .weekdays(weekdays)
                .startTime(startTime)
                .endTime(endTime)
                .room(blankToNull(req.getRoom()))
                .capacity(req.getCapacity())
                .build();

        return toDetailResponse(classRepository.save(clazz));
    }

    @Transactional
    public ClassDto.DetailResponse updateClass(Long academyId, Long classId, ClassDto.UpdateRequest req) {
        ReaClass clazz = findClassInAcademy(academyId, classId);

        if (req.getName() != null && req.getName().isBlank()) {
            throw new IllegalArgumentException("반 이름은 필수입니다.");
        }

        Long teacherId = req.getTeacherId();
        if (teacherId != null) {
            requireTeacher(academyId, teacherId);
        }

        String weekdays = req.getWeekdays() == null ? clazz.getWeekdays()
                : normalizeWeekdays(req.getWeekdays());
        LocalTime startTime = req.getStartTime() == null ? clazz.getStartTime()
                : parseTimeOrNull(req.getStartTime());
        LocalTime endTime = req.getEndTime() == null ? clazz.getEndTime()
                : parseTimeOrNull(req.getEndTime());
        validateTimeRange(startTime, endTime);
        Integer capacity = req.getCapacity() == null ? clazz.getCapacity() : req.getCapacity();
        validateCapacity(capacity);

        clazz.update(req.getName(), req.getDescription(), teacherId,
                weekdays, startTime, endTime, req.getRoom(), capacity);
        return toDetailResponse(clazz);
    }

    @Transactional
    public void closeClass(Long academyId, Long classId) {
        ReaClass clazz = findClassInAcademy(academyId, classId);
        if (!clazz.getActive()) {
            throw new IllegalStateException("이미 종료된 반입니다.");
        }
        // 소속 학생 배정 해제 (이력 보존)
        List<ClassEnrollment> enrollments = enrollmentRepository.findByClassIdAndActiveTrue(classId);
        enrollments.forEach(ClassEnrollment::deactivate);
        clazz.close();
    }

    // ===== 반 조회 =====

    public List<ClassDto.Response> getClasses(Long academyId, boolean includeInactive) {
        List<ReaClass> classes = includeInactive
                ? classRepository.findByAcademyIdOrderByIdAsc(academyId)
                : classRepository.findByAcademyIdAndActiveTrueOrderByIdAsc(academyId);
        return classes.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ClassDto.DetailResponse getClassDetail(Long academyId, Long classId) {
        ReaClass clazz = findClassInAcademy(academyId, classId);
        return toDetailResponse(clazz);
    }

    // ===== 학생 배정·제외·이동 =====

    @Transactional
    public ClassDto.DetailResponse assignStudents(Long academyId, Long classId, List<Long> studentIds) {
        ReaClass clazz = requireActiveClass(academyId, classId);
        if (studentIds == null || studentIds.isEmpty()) {
            throw new IllegalArgumentException("배정할 학생을 선택하세요.");
        }

        // 중복 제거
        List<Long> distinctIds = studentIds.stream().distinct().collect(Collectors.toList());

        // 이미 배정된 학생은 제외 (idempotent)
        List<Long> newIds = distinctIds.stream()
                .filter(id -> enrollmentRepository
                        .findByClassIdAndStudentIdAndActiveTrue(classId, id).isEmpty())
                .collect(Collectors.toList());

        // 학생 검증: 같은 학원의 활성 학생만
        for (Long studentId : newIds) {
            requireStudent(academyId, studentId);
        }

        // 정원 검증
        if (clazz.getCapacity() != null) {
            long current = enrollmentRepository.countByClassIdAndActiveTrue(classId);
            if (current + newIds.size() > clazz.getCapacity()) {
                throw new IllegalStateException(
                        "정원(" + clazz.getCapacity() + "명)을 초과합니다. 현재 " + current + "명.");
            }
        }

        for (Long studentId : newIds) {
            enrollmentRepository.save(ClassEnrollment.builder()
                    .academyId(academyId)
                    .classId(classId)
                    .studentId(studentId)
                    .build());
        }
        return toDetailResponse(clazz);
    }

    @Transactional
    public void removeStudent(Long academyId, Long classId, Long studentId) {
        ReaClass clazz = requireActiveClass(academyId, classId);
        requireStudent(academyId, studentId);

        ClassEnrollment enrollment = enrollmentRepository
                .findByClassIdAndStudentIdAndActiveTrue(clazz.getId(), studentId)
                .orElseThrow(() -> new IllegalArgumentException("이 반에 소속된 학생이 아닙니다."));
        enrollment.deactivate();
    }

    @Transactional
    public ClassDto.DetailResponse moveStudent(Long academyId, Long classId, Long studentId, Long toClassId) {
        if (classId.equals(toClassId)) {
            throw new IllegalArgumentException("같은 반으로는 이동할 수 없습니다.");
        }
        ReaClass fromClass = requireActiveClass(academyId, classId);
        ReaClass toClass = requireActiveClass(academyId, toClassId);
        requireStudent(academyId, studentId);

        // 1. 기존 반에서 제외
        enrollmentRepository.findByClassIdAndStudentIdAndActiveTrue(fromClass.getId(), studentId)
                .orElseThrow(() -> new IllegalArgumentException("이 반에 소속된 학생이 아닙니다."))
                .deactivate();

        // 2. 대상 반에 배정 (이미 소속이면 중복 등록 없이 종료)
        if (enrollmentRepository.findByClassIdAndStudentIdAndActiveTrue(toClassId, studentId).isEmpty()) {
            if (toClass.getCapacity() != null) {
                long current = enrollmentRepository.countByClassIdAndActiveTrue(toClassId);
                if (current + 1 > toClass.getCapacity()) {
                    throw new IllegalStateException(
                            "대상 반 정원(" + toClass.getCapacity() + "명)을 초과합니다.");
                }
            }
            enrollmentRepository.save(ClassEnrollment.builder()
                    .academyId(academyId)
                    .classId(toClassId)
                    .studentId(studentId)
                    .build());
        }
        return toDetailResponse(toClass);
    }

    // ===== 주간 시간표 =====

    /**
     * 학원의 주간 시간표.
     * 활성 반의 요일·시간 정보를 일자별로 펼쳐 반환.
     * 요일 순서(월~일), 시작 시각 순으로 정렬.
     */
    public List<ClassDto.TimetableEntry> getWeeklyTimetable(Long academyId) {
        List<ReaClass> classes = classRepository.findByAcademyIdAndActiveTrueOrderByIdAsc(academyId);
        List<ClassDto.TimetableEntry> entries = new ArrayList<>();
        for (ReaClass clazz : classes) {
            List<String> weekdays = toWeekdayList(clazz.getWeekdays());
            if (weekdays.isEmpty()) {
                continue; // 시간표 미지정 반은 제외
            }
            String teacherName = teacherNameOf(clazz.getTeacherId());
            long studentCount = enrollmentRepository.countByClassIdAndActiveTrue(clazz.getId());
            for (String day : weekdays) {
                entries.add(ClassDto.TimetableEntry.builder()
                        .classId(clazz.getId())
                        .className(clazz.getName())
                        .dayOfWeek(day)
                        .startTime(formatTime(clazz.getStartTime()))
                        .endTime(formatTime(clazz.getEndTime()))
                        .room(clazz.getRoom())
                        .teacherName(teacherName)
                        .studentCount(studentCount)
                        .build());
            }
        }
        entries.sort(Comparator
                .comparing((ClassDto.TimetableEntry e) -> parseDayCode(e.getDayOfWeek()).getValue())
                .thenComparing(e -> e.getStartTime() == null ? "99:99" : e.getStartTime())
                .thenComparing(ClassDto.TimetableEntry::getClassName));
        return entries;
    }

    // ===== 검증·조회 헬퍼 =====

    /** 학원 내 반 조회 (타 학원 접근 불가). 비활성 반도 조회 가능. */
    private ReaClass findClassInAcademy(Long academyId, Long classId) {
        return classRepository.findById(classId)
                .filter(c -> c.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("반을 찾을 수 없습니다."));
    }

    /** 학생 배정·제외·이동 대상은 활성 반만 허용 */
    private ReaClass requireActiveClass(Long academyId, Long classId) {
        ReaClass clazz = findClassInAcademy(academyId, classId);
        if (!clazz.getActive()) {
            throw new IllegalStateException("종료된 반입니다.");
        }
        return clazz;
    }

    /** 담당 강사 검증: 같은 학원의 활성 원장·강사만 */
    private ReaMember requireTeacher(Long academyId, Long teacherId) {
        return memberRepository.findById(teacherId)
                .filter(m -> m.getActive() && m.getAcademyId().equals(academyId)
                        && (m.getRole() == ReaRole.TEACHER || m.getRole() == ReaRole.ACADEMY_OWNER))
                .orElseThrow(() -> new IllegalArgumentException("담당 강사를 찾을 수 없습니다."));
    }

    /** 학생 검증: 같은 학원의 활성 학생만 */
    private ReaStudent requireStudent(Long academyId, Long studentId) {
        return studentRepository.findById(studentId)
                .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));
    }

    private String teacherNameOf(Long teacherId) {
        return memberRepository.findById(teacherId)
                .map(ReaMember::getName)
                .orElse("-");
    }

    private void validateCapacity(Integer capacity) {
        if (capacity != null && capacity < 1) {
            throw new IllegalArgumentException("정원은 1명 이상이어야 합니다.");
        }
    }

    private void validateTimeRange(LocalTime start, LocalTime end) {
        if (start != null && end != null && !start.isBefore(end)) {
            throw new IllegalArgumentException("시작 시각은 종료 시각보다 빨라야 합니다.");
        }
    }

    /** 요일 리스트 → "MON,WED,FRI" 저장 형식. null이면 null 반환. */
    private String normalizeWeekdays(List<String> weekdays) {
        if (weekdays == null) return null;
        List<String> normalized = weekdays.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> d.trim().toUpperCase())
                .peek(ClassService::parseDayCode)
                .distinct()
                .sorted(Comparator.comparing(d -> parseDayCode(d).getValue()))
                .collect(Collectors.toList());
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    private List<String> toWeekdayList(String weekdays) {
        if (weekdays == null || weekdays.isBlank()) return List.of();
        return List.of(weekdays.split(","));
    }

    private LocalTime parseTime(String time) {
        if (time == null || time.isBlank()) return null;
        try {
            return LocalTime.parse(time.trim(), TIME_FORMAT);
        } catch (Exception e) {
            throw new IllegalArgumentException("시각 형식이 잘못되었습니다 (HH:mm): " + time);
        }
    }

    private LocalTime parseTimeOrNull(String time) {
        return parseTime(time);
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ===== DTO 변환 =====

    private ClassDto.Response toResponse(ReaClass c) {
        return ClassDto.Response.builder()
                .id(c.getId())
                .academyId(c.getAcademyId())
                .name(c.getName())
                .description(c.getDescription())
                .teacherId(c.getTeacherId())
                .teacherName(teacherNameOf(c.getTeacherId()))
                .weekdays(toWeekdayList(c.getWeekdays()))
                .startTime(formatTime(c.getStartTime()))
                .endTime(formatTime(c.getEndTime()))
                .room(c.getRoom())
                .capacity(c.getCapacity())
                .studentCount(enrollmentRepository.countByClassIdAndActiveTrue(c.getId()))
                .active(c.getActive())
                .build();
    }

    private ClassDto.DetailResponse toDetailResponse(ReaClass c) {
        List<Long> studentIds = enrollmentRepository.findByClassIdAndActiveTrue(c.getId())
                .stream()
                .map(ClassEnrollment::getStudentId)
                .collect(Collectors.toList());
        List<StudentDto.Response> students = studentIds.stream()
                .map(id -> studentRepository.findById(id).orElse(null))
                .filter(s -> s != null && s.getActive())
                .map(s -> StudentDto.Response.builder()
                        .id(s.getId())
                        .academyId(s.getAcademyId())
                        .name(s.getName())
                        .grade(s.getGrade())
                        .schoolName(s.getSchoolName())
                        .parentName(s.getParentName())
                        .parentPhone(s.getParentPhone())
                        .active(s.getActive())
                        .build())
                .collect(Collectors.toList());

        return ClassDto.DetailResponse.builder()
                .id(c.getId())
                .academyId(c.getAcademyId())
                .name(c.getName())
                .description(c.getDescription())
                .teacherId(c.getTeacherId())
                .teacherName(teacherNameOf(c.getTeacherId()))
                .weekdays(toWeekdayList(c.getWeekdays()))
                .startTime(formatTime(c.getStartTime()))
                .endTime(formatTime(c.getEndTime()))
                .room(c.getRoom())
                .capacity(c.getCapacity())
                .studentCount(students.size())
                .active(c.getActive())
                .students(students)
                .build();
    }
}
