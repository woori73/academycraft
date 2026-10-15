package com.rea.academy.agent.tools;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.agent.AgentUtils;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.service.ClassService;
import com.rea.academy.service.LearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 숙제 미제출 학생 조회 도구 (읽기).
 * 입력: 반ID(필수), 숙제ID(선택).
 * 숙제ID가 없으면 해당 반의 전체 숙제를 대상으로 집계한다.
 * 검사 기록이 COMPLETED가 아닌 학생(미검사 포함)을 미제출로 본다.
 */
@Component
@RequiredArgsConstructor
public class GetHomeworkIncompleteTool implements AgentTool {

    private final LearningService learningService;
    private final ClassService classService;

    @Override
    public String getName() {
        return "getHomeworkIncomplete";
    }

    @Override
    public String getDescription() {
        return "반의 숙제 미제출(미완료·미검사) 학생 목록을 조회한다. (읽기)";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("classId", "반 ID (className과 둘 중 하나 필수)");
        schema.put("className*", "반 이름 (선택, 예: 영어반. classId가 없을 때 사용)");
        schema.put("homeworkId*", "숙제 ID (선택, 없으면 해당 반 전체 숙제 대상)");
        return schema;
    }

    @Override
    public boolean isWrite() {
        return false;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
        Long classId = AgentUtils.optionalLong(input, "classId");
        String className = AgentUtils.optionalString(input, "className");
        if (classId == null && className != null) {
            classId = findClassIdByName(ctx.academyId(), className);
        }
        if (classId == null) {
            throw new IllegalArgumentException("반 ID 또는 반 이름은 필수입니다.");
        }
        Long homeworkId = AgentUtils.optionalLong(input, "homeworkId");

        ClassDto.DetailResponse clazz = classService.getClassDetail(ctx.academyId(), classId);
        Map<Long, String> enrolled = clazz.getStudents().stream()
                .collect(Collectors.toMap(StudentDto.Response::getId,
                        StudentDto.Response::getName, (a, b) -> a));

        List<LearningDto.HomeworkResponse> homeworks;
        if (homeworkId != null) {
            homeworks = learningService.listHomework(ctx.academyId(), classId, null, null).stream()
                    .filter(h -> h.getId().equals(homeworkId))
                    .collect(Collectors.toList());
            if (homeworks.isEmpty()) {
                throw new IllegalArgumentException("숙제를 찾을 수 없습니다. (homeworkId=" + homeworkId + ")");
            }
        } else {
            homeworks = learningService.listHomework(ctx.academyId(), classId, null, null);
        }

        List<Map<String, Object>> incomplete = new ArrayList<>();
        for (LearningDto.HomeworkResponse homework : homeworks) {
            Map<Long, String> completedStudentIds = learningService
                    .listHomeworkChecks(ctx.academyId(), homework.getId()).stream()
                    .filter(c -> "COMPLETED".equals(c.getStatus()))
                    .collect(Collectors.toMap(
                            LearningDto.HomeworkCheckResponse::getStudentId,
                            LearningDto.HomeworkCheckResponse::getStudentName, (a, b) -> a));
            for (Map.Entry<Long, String> student : enrolled.entrySet()) {
                if (!completedStudentIds.containsKey(student.getKey())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("studentId", student.getKey());
                    row.put("studentName", student.getValue());
                    row.put("homeworkId", homework.getId());
                    row.put("homeworkTitle", homework.getTitle());
                    incomplete.add(row);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId", clazz.getId());
        result.put("className", clazz.getName());
        result.put("homeworkId", homeworkId);
        result.put("incompleteCount", incomplete.size());
        result.put("students", incomplete);
        return result;
    }

    /** 반 이름 → 반 ID (정확히 일치 우선, 없으면 포함 검색) */
    private Long findClassIdByName(Long academyId, String name) {
        return classService.getClasses(academyId, false).stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .or(() -> classService.getClasses(academyId, false).stream()
                        .filter(c -> c.getName() != null && c.getName().contains(name))
                        .findFirst())
                .map(ClassDto.Response::getId)
                .orElseThrow(() -> new IllegalArgumentException("반을 찾을 수 없습니다: " + name));
    }
}
