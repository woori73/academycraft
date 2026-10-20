package com.rea.academy.agent.tools;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.agent.AgentUtils;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.service.ClassService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 학생 반 배정 도구 (쓰기).
 * 입력: 학생ID(필수), 반ID(필수). ClassService 재사용.
 */
@Component
@RequiredArgsConstructor
public class AssignStudentToClassTool implements AgentTool {

    private final ClassService classService;
    private final com.rea.academy.domain.member.ReaStudentRepository studentRepository;
    private final com.rea.academy.domain.clazz.ReaClassRepository classRepository;
    private final com.rea.academy.domain.clazz.ClassEnrollmentRepository enrollmentRepository;

    @Override
    public String getName() {
        return "assignStudentToClass";
    }

    @Override
    public String getDescription() {
        return "학생을 지정된 반에 배정한다. (쓰기: 승인 필요)";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("studentId", "학생 ID (필수)");
        schema.put("classId", "반 ID (필수)");
        return schema;
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
        Long studentId = AgentUtils.requiredLong(input, "studentId", "학생 ID");
        Long classId = AgentUtils.requiredLong(input, "classId", "반 ID");

        ClassDto.DetailResponse clazz = classService.assignStudents(
                ctx.academyId(), classId, List.of(studentId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("classId", clazz.getId());
        result.put("className", clazz.getName());
        return result;
    }

    @Override
    public Map<String, Object> preview(Map<String, Object> input, AgentContext ctx) {
        Long studentId = AgentUtils.requiredLong(input, "studentId", "학생 ID");
        Long classId = AgentUtils.requiredLong(input, "classId", "반 ID");

        var student = studentRepository.findById(studentId)
                .filter(s -> ctx.academyId().equals(s.getAcademyId()) && Boolean.TRUE.equals(s.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("이 학원의 재원 학생이 아닙니다: " + studentId));
        var clazz = classRepository.findById(classId)
                .filter(c -> ctx.academyId().equals(c.getAcademyId()) && Boolean.TRUE.equals(c.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("이 학원의 운영 중인 반이 아닙니다: " + classId));

        boolean already = enrollmentRepository
                .findByClassIdAndStudentIdAndActiveTrue(classId, studentId).isPresent();
        if (!already && clazz.getCapacity() != null) {
            long current = enrollmentRepository.countByClassIdAndActiveTrue(classId);
            if (current + 1 > clazz.getCapacity()) {
                throw new IllegalStateException(
                        "정원(" + clazz.getCapacity() + "명)을 초과합니다. 현재 " + current + "명.");
            }
        }
        List<String> currentClasses = enrollmentRepository.findByStudentIdAndActiveTrue(studentId).stream()
                .map(e -> classRepository.findById(e.getClassId())
                        .filter(c -> ctx.academyId().equals(c.getAcademyId()))
                        .map(c -> c.getName()).orElse("반 " + e.getClassId()))
                .sorted()
                .toList();

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("학생", student.getName() + " (ID " + studentId + ")");
        changes.put("배정할 반", clazz.getName() + " (ID " + classId + ")");
        changes.put("현재 소속", currentClasses.isEmpty() ? "없음" : String.join(", ", currentClasses));
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("summary", already ? "이미 이 반에 배정된 학생입니다 (변경 없음)" : "학생을 반에 배정합니다");
        preview.put("changes", changes);
        preview.put("notice", "승인 전에는 아무것도 저장되지 않습니다. 기존 소속은 유지되고 이 반이 추가됩니다.");
        return preview;
    }
}
