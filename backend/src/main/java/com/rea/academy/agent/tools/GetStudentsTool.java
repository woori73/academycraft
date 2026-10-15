package com.rea.academy.agent.tools;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.agent.AgentUtils;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.service.ClassService;
import com.rea.academy.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 학생 목록 조회 도구 (읽기).
 * 입력: 반ID(선택), 반이름(선택, 예: "영어반"), 학생 이름 검색어(선택).
 * 반ID·반이름이 모두 없으면 학원 전체 활성 학생 목록을 반환한다.
 * 학부모 연락처 등 민감 정보는 반환하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class GetStudentsTool implements AgentTool {

    private final ClassService classService;
    private final MemberService memberService;

    @Override
    public String getName() {
        return "getStudents";
    }

    @Override
    public String getDescription() {
        return "학원 또는 반의 학생 목록(이름·학년)을 조회한다. (읽기)";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("classId*", "반 ID (선택, 없으면 className 또는 전체 학원)");
        schema.put("className*", "반 이름 (선택, 예: 영어반)");
        schema.put("nameKeyword*", "학생 이름 검색어 (선택)");
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
        String nameKeyword = AgentUtils.optionalString(input, "nameKeyword");

        List<StudentDto.Response> students;
        Long resolvedClassId = classId;
        String resolvedClassName = null;

        if (classId != null) {
            ClassDto.DetailResponse detail = classService.getClassDetail(ctx.academyId(), classId);
            resolvedClassName = detail.getName();
            students = detail.getStudents();
        } else if (className != null) {
            ClassDto.Response clazz = findClassByName(ctx.academyId(), className);
            resolvedClassId = clazz.getId();
            resolvedClassName = clazz.getName();
            students = classService.getClassDetail(ctx.academyId(), clazz.getId()).getStudents();
        } else {
            students = memberService.getStudents(ctx.academyId(), nameKeyword);
        }

        if (nameKeyword != null && resolvedClassId != null) {
            String kw = nameKeyword;
            students = students.stream()
                    .filter(s -> s.getName() != null && s.getName().contains(kw))
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> rows = students.stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", s.getId());
            row.put("studentName", s.getName());
            row.put("grade", s.getGrade());
            return row;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId", resolvedClassId);
        result.put("className", resolvedClassName);
        result.put("count", rows.size());
        result.put("students", rows);
        return result;
    }

    /** 반 이름으로 반 조회 (정확히 일치 우선, 없으면 포함 검색) */
    private ClassDto.Response findClassByName(Long academyId, String name) {
        List<ClassDto.Response> classes = classService.getClasses(academyId, false);
        return classes.stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .or(() -> classes.stream()
                        .filter(c -> c.getName() != null && c.getName().contains(name))
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException("반을 찾을 수 없습니다: " + name));
    }
}
