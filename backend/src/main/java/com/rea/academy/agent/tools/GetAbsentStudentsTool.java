package com.rea.academy.agent.tools;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.agent.AgentUtils;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.service.AttendanceService;
import com.rea.academy.service.ClassService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 결석 학생 조회 도구 (읽기).
 * 입력: 날짜(선택, 기본 오늘. "오늘"/"어제"/"yyyy-MM-dd").
 * 모든 활성 반의 명부를 확인해 결석(ABSENT) 학생 목록을 반환한다.
 */
@Component
@RequiredArgsConstructor
public class GetAbsentStudentsTool implements AgentTool {

    private final ClassService classService;
    private final AttendanceService attendanceService;

    @Override
    public String getName() {
        return "getAbsentStudents";
    }

    @Override
    public String getDescription() {
        return "지정 날짜의 결석 학생 목록을 조회한다. (읽기)";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("date*", "조회 날짜 (선택, 기본 오늘. \"오늘\"/\"어제\"/\"yyyy-MM-dd\")");
        return schema;
    }

    @Override
    public boolean isWrite() {
        return false;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
        LocalDate date = AgentUtils.parseDate(input.get("date"), LocalDate.now());
        String dateStr = date.toString();

        List<ClassDto.Response> classes = classService.getClasses(ctx.academyId(), false);
        List<Map<String, Object>> absentees = new ArrayList<>();
        for (ClassDto.Response clazz : classes) {
            AttendanceDto.RosterResponse roster =
                    attendanceService.getRoster(ctx.academyId(), clazz.getId(), dateStr);
            for (AttendanceDto.RosterEntry entry : roster.getEntries()) {
                if ("ABSENT".equals(entry.getStatus())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("studentId", entry.getStudentId());
                    row.put("studentName", entry.getStudentName());
                    row.put("classId", roster.getClassId());
                    row.put("className", roster.getClassName());
                    absentees.add(row);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", dateStr);
        result.put("count", absentees.size());
        result.put("absentees", absentees);
        return result;
    }
}
