package com.rea.academy.agent.tools;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.agent.AgentUtils;
import com.rea.academy.dto.LearningDto;
import com.rea.academy.service.LearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 성적 변화 분석 도구 (읽기).
 * 입력: 학생ID(필수), 시작일(선택, 기본 90일 전), 종료일(선택, 기본 오늘).
 * 과목별 평균 백분율·첫 성적·최근 성적·추이(상승/하락/유지)를 계산하고 요약 텍스트를 생성한다.
 */
@Component
@RequiredArgsConstructor
public class AnalyzeGradeChangesTool implements AgentTool {

    /** 추이 판정 임계 (백분율 포인트) */
    private static final double TREND_THRESHOLD = 5.0;

    private final LearningService learningService;

    @Override
    public String getName() {
        return "analyzeGradeChanges";
    }

    @Override
    public String getDescription() {
        return "학생의 성적 변화를 분석한다 (과목별 평균·추이). (읽기)";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("studentId", "학생 ID (필수)");
        schema.put("startDate*", "시작일 (선택, 기본 90일 전. yyyy-MM-dd)");
        schema.put("endDate*", "종료일 (선택, 기본 오늘. yyyy-MM-dd)");
        return schema;
    }

    @Override
    public boolean isWrite() {
        return false;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
        Long studentId = AgentUtils.requiredLong(input, "studentId", "학생 ID");
        LocalDate end = AgentUtils.parseDate(input.get("endDate"), LocalDate.now());
        LocalDate start = AgentUtils.parseDate(input.get("startDate"), end.minusDays(89));
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }

        List<LearningDto.GradeResponse> grades = learningService
                .getGrades(ctx.academyId(), studentId, null).stream()
                .filter(g -> g.getExamDate() != null
                        && !g.getExamDate().isBefore(start)
                        && !g.getExamDate().isAfter(end)
                        && g.getRate() != null)
                .sorted(Comparator.comparing(LearningDto.GradeResponse::getExamDate)
                        .thenComparing(LearningDto.GradeResponse::getId))
                .collect(Collectors.toList());

        Map<String, List<LearningDto.GradeResponse>> bySubject = grades.stream()
                .collect(Collectors.groupingBy(
                        g -> g.getSubject() == null ? "(과목 미지정)" : g.getSubject(),
                        TreeMap::new, Collectors.toList()));

        List<Map<String, Object>> subjects = new ArrayList<>();
        double totalAvg = 0;
        int totalCount = 0;
        List<String> trendLines = new ArrayList<>();
        for (Map.Entry<String, List<LearningDto.GradeResponse>> e : bySubject.entrySet()) {
            List<LearningDto.GradeResponse> list = e.getValue();
            double avg = list.stream().mapToDouble(LearningDto.GradeResponse::getRate).average().orElse(0);
            double first = list.get(0).getRate();
            double last = list.get(list.size() - 1).getRate();
            String trend = judgeTrend(first, last);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", e.getKey());
            row.put("count", list.size());
            row.put("avgRate", round1(avg));
            row.put("firstRate", round1(first));
            row.put("lastRate", round1(last));
            row.put("trend", trend);
            subjects.add(row);

            totalAvg += avg * list.size();
            totalCount += list.size();
            trendLines.add(e.getKey() + ": " + round1(first) + "%→" + round1(last) + "% (" + trend + ")");
        }

        String summary;
        if (totalCount == 0) {
            summary = "기간 내 성적 기록이 없습니다.";
        } else {
            summary = "총 " + totalCount + "건, 전체 평균 " + round1(totalAvg / totalCount)
                    + "%. " + String.join(" ", trendLines);
        }

        String studentName = grades.isEmpty() ? null : grades.get(0).getStudentName();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("studentName", studentName);
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("gradeCount", totalCount);
        result.put("overallAvgRate", totalCount == 0 ? null : round1(totalAvg / totalCount));
        result.put("subjects", subjects);
        result.put("summary", summary);
        return result;
    }

    private String judgeTrend(double first, double last) {
        double diff = last - first;
        if (diff >= TREND_THRESHOLD) {
            return "상승";
        }
        if (diff <= -TREND_THRESHOLD) {
            return "하락";
        }
        return "유지";
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
