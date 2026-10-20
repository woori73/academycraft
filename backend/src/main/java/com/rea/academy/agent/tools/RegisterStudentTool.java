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

/**
 * 학생 등록 도구 (쓰기).
 * 입력: 이름(필수), 반ID(선택), 학부모이름·학부모전화(선택), 학년·학교명(선택).
 * MemberService 재사용. 반ID가 있으면 등록 직후 반에 배정한다.
 */
@Component
@RequiredArgsConstructor
public class RegisterStudentTool implements AgentTool {

    private final MemberService memberService;
    private final ClassService classService;
    private final com.rea.academy.domain.clazz.ReaClassRepository classRepository;

    @Override
    public String getName() {
        return "registerStudent";
    }

    @Override
    public String getDescription() {
        return "새 학생을 등록하고 인증키를 발급한다. 반ID가 있으면 해당 반에 함께 배정한다. (쓰기: 승인 필요)";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("name", "학생 이름 (필수)");
        schema.put("classId*", "배정할 반 ID (선택)");
        schema.put("parentName*", "학부모 이름 (선택)");
        schema.put("parentPhone*", "학부모 전화번호 (선택)");
        schema.put("grade*", "학년 (선택, 예: 고2)");
        schema.put("schoolName*", "학교명 (선택)");
        return schema;
    }

    @Override
    public boolean isWrite() {
        return true;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
        String name = AgentUtils.requiredString(input, "name", "학생 이름");
        Long classId = AgentUtils.optionalLong(input, "classId");

        StudentDto.DetailResponse created = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ctx.academyId())
                        .name(name)
                        .grade(AgentUtils.optionalString(input, "grade"))
                        .schoolName(AgentUtils.optionalString(input, "schoolName"))
                        .parentName(AgentUtils.optionalString(input, "parentName"))
                        .parentPhone(AgentUtils.optionalString(input, "parentPhone"))
                        .build());

        String assignedClassName = null;
        if (classId != null) {
            ClassDto.DetailResponse clazz = classService.assignStudents(
                    ctx.academyId(), classId, List.of(created.getId()));
            assignedClassName = clazz.getName();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", created.getId());
        result.put("name", created.getName());
        result.put("authKey", created.getAuthKey());
        result.put("classId", classId);
        result.put("className", assignedClassName);
        return result;
    }

    @Override
    public Map<String, Object> preview(Map<String, Object> input, AgentContext ctx) {
        String name = AgentUtils.requiredString(input, "name", "학생 이름");
        if (name.length() > 50) {
            throw new IllegalArgumentException("학생 이름은 50자 이하여야 합니다.");
        }
        Long classId = AgentUtils.optionalLong(input, "classId");
        String className = null;
        if (classId != null) {
            className = classRepository.findById(classId)
                    .filter(c -> ctx.academyId().equals(c.getAcademyId()) && Boolean.TRUE.equals(c.getActive()))
                    .map(c -> c.getName())
                    .orElseThrow(() -> new IllegalArgumentException("이 학원의 운영 중인 반이 아닙니다: " + classId));
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("학생 이름", name);
        putIfPresent(changes, "학년", AgentUtils.optionalString(input, "grade"));
        putIfPresent(changes, "학교", AgentUtils.optionalString(input, "schoolName"));
        putIfPresent(changes, "학부모", AgentUtils.optionalString(input, "parentName"));
        putIfPresent(changes, "학부모 연락처", AgentUtils.optionalString(input, "parentPhone"));
        if (className != null) {
            changes.put("배정할 반", className + " (ID " + classId + ")");
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("summary", "새 학생을 등록합니다");
        preview.put("changes", changes);
        preview.put("notice", "승인 전에는 아무것도 저장되지 않습니다. 학생 인증키는 승인 직후 한 번만 표시됩니다.");
        return preview;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
