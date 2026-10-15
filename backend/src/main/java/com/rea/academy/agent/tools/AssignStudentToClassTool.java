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
}
