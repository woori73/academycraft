package com.rea.academy.agent.tools;

import com.rea.academy.common.ReaTime;
import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.agent.AgentUtils;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.service.AttendanceService;
import com.rea.academy.service.ClassService;
import com.rea.academy.service.ConsultationService;
import com.rea.academy.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영 현황 보고서 생성 도구 (읽기).
 * 입력: 시작일(선택, 기본 30일 전), 종료일(선택, 기본 오늘).
 * 출결률(명부 집계)·공지 수·상담 수(상태별)를 집계하고 한글 텍스트 보고서를 생성한다.
 * 읽기 전용이므로 외부 메시지 발송은 하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class GenerateOperationReportTool implements AgentTool {

    private final ClassService classService;
    private final AttendanceService attendanceService;
    private final NoticeService noticeService;
    private final ConsultationService consultationService;

    @Override
    public String getName() {
        return "generateOperationReport";
    }

    @Override
    public String getDescription() {
        return "학원 운영 현황 보고서(출결률·공지·상담 현황)를 생성한다. (읽기)";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new LinkedHashMap<>();
        schema.put("startDate*", "시작일 (선택, 기본 30일 전. yyyy-MM-dd)");
        schema.put("endDate*", "종료일 (선택, 기본 오늘. yyyy-MM-dd)");
        return schema;
    }

    @Override
    public boolean isWrite() {
        return false;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
        LocalDate end = AgentUtils.parseDate(input.get("endDate"), ReaTime.today());
        LocalDate start = AgentUtils.parseDate(input.get("startDate"), end.minusDays(29));
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("조회 시작일이 종료일보다 늦습니다.");
        }

        // --- 출결률: 활성 반 × 기간 일자별 명부 집계 ---
        List<ClassDto.Response> classes = classService.getClasses(ctx.academyId(), false);
        long attended = 0;
        long total = 0;
        long absent = 0;
        long late = 0;
        long earlyLeave = 0;
        for (ClassDto.Response clazz : classes) {
            LocalDate day = start;
            while (!day.isAfter(end)) {
                AttendanceDto.RosterResponse roster =
                        attendanceService.getRoster(ctx.academyId(), clazz.getId(), day.toString());
                for (AttendanceDto.RosterEntry entry : roster.getEntries()) {
                    total++;
                    switch (entry.getStatus() == null ? "" : entry.getStatus()) {
                        case "PRESENT", "CHECKED_OUT", "LATE", "EARLY_LEAVE" -> attended++;
                        default -> {
                        }
                    }
                    if ("ABSENT".equals(entry.getStatus())) {
                        absent++;
                    }
                    if ("LATE".equals(entry.getStatus())) {
                        late++;
                    }
                    if ("EARLY_LEAVE".equals(entry.getStatus())) {
                        earlyLeave++;
                    }
                }
                day = day.plusDays(1);
            }
        }
        double attendanceRate = total == 0 ? 0 : Math.round((attended * 1000.0 / total)) / 10.0;

        // --- 공지·상담 ---
        List<NoticeDto.ListResponse> notices =
                noticeService.list(ctx.academyId(), null, null, null, null);
        List<ConsultationDto.ListResponse> consultations =
                consultationService.list(ctx.academyId(), null, null, null, null, null);
        long openConsultations = consultations.stream()
                .filter(c -> "OPEN".equals(c.getStatus())).count();
        long answeredConsultations = consultations.stream()
                .filter(c -> "ANSWERED".equals(c.getStatus())).count();
        long closedConsultations = consultations.stream()
                .filter(c -> "CLOSED".equals(c.getStatus())).count();

        StringBuilder text = new StringBuilder();
        text.append("운영 현황 보고서 (").append(start).append(" ~ ").append(end).append(")\n");
        text.append("- 대상 반: ").append(classes.size()).append("개\n");
        text.append("- 출결률: ").append(attendanceRate).append("% (등원 ").append(attended)
                .append("/명부 ").append(total).append(")\n");
        text.append("- 결석 ").append(absent).append("건, 지각 ").append(late)
                .append("건, 조퇴 ").append(earlyLeave).append("건\n");
        text.append("- 공지: ").append(notices.size()).append("건\n");
        text.append("- 상담: 총 ").append(consultations.size()).append("건 (진행중 ")
                .append(openConsultations).append(", 답변완료 ").append(answeredConsultations)
                .append(", 종료 ").append(closedConsultations).append(")");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("classCount", classes.size());
        result.put("attendanceRate", attendanceRate);
        result.put("attendedCount", attended);
        result.put("rosterCount", total);
        result.put("absentCount", absent);
        result.put("lateCount", late);
        result.put("earlyLeaveCount", earlyLeave);
        result.put("noticeCount", notices.size());
        result.put("consultationCount", consultations.size());
        result.put("openConsultationCount", openConsultations);
        result.put("answeredConsultationCount", answeredConsultations);
        result.put("closedConsultationCount", closedConsultations);
        result.put("report", text.toString());
        return result;
    }
}
