package com.rea.academy.agent;

import com.rea.academy.common.ReaTime;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자연어 명령 → 도구 호출로 변환하는 키워드/패턴 기반 파서 (2단계).
 *
 * 유료 API·LLM 없이 규칙 기반으로 동작한다.
 * 파싱 실패 시에는 명령에 포함된 키워드로 후보 도구를 제안한다.
 */
@Component
public class CommandParser {

    /** 파싱 결과 */
    public record ParseResult(
            boolean ok,
            String toolName,
            Map<String, Object> params,
            List<String> suggestions,
            String message) {

        public static ParseResult success(String toolName, Map<String, Object> params) {
            return new ParseResult(true, toolName, params, List.of(), null);
        }

        public static ParseResult failure(String message, List<String> suggestions) {
            return new ParseResult(false, null, Map.of(), suggestions, message);
        }
    }

    private static final Pattern ID_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("[\"'“”]([^\"'“”]+)[\"'“”]");
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern NAME_KW = Pattern.compile(
            "(?:학생\\s*등록|등록|신규)\\s*([가-힣]{2,5})|([가-힣]{2,5})\\s*(?:학생)?\\s*(?:등록|신규)");
    private static final Pattern CLASS_KW = Pattern.compile("(\\d+)\\s*반");
    private static final Pattern HOMEWORK_KW = Pattern.compile("숙제\\s*(\\d+)");
    private static final Pattern CLASS_NAME_KW = Pattern.compile("([가-힣A-Za-z0-9]{1,20})반");
    /** 파괴적 작업 키워드 (삭제·제거 등) — 에이전트 실행 금지 */
    private static final Pattern DESTRUCTIVE_KW = Pattern.compile("삭제|지우|제거|탈퇴|파기");

    /**
     * 자연어 명령을 도구 호출로 변환한다.
     *
     * @param command 자연어 명령 (null·빈 문자열 불가)
     * @return 파싱 결과
     */
    public ParseResult parse(String command) {
        if (command == null || command.isBlank()) {
            return ParseResult.failure("명령이 비어 있습니다.", List.of());
        }
        String cmd = command.trim();

        ParseResult matched = tryMatch(cmd);
        if (matched != null) {
            return matched;
        }
        return ParseResult.failure(
                "명령을 이해하지 못했습니다. (예: \"학생 등록 김철수\", \"오늘 결석 학생\", \"공지 작성\")",
                suggest(cmd));
    }

    private ParseResult tryMatch(String cmd) {
        // 0. 파괴적 작업(삭제·제거 등): 도구 없이 명시적 거부. 어떤 규칙보다 먼저 검사한다.
        if (DESTRUCTIVE_KW.matcher(cmd).find()) {
            return ParseResult.failure(
                    "파괴적 작업(삭제·제거 등)은 에이전트로 실행할 수 없습니다. "
                            + "필요한 경우 관리 화면에서 직접 처리하세요.",
                    List.of());
        }
        // 1. 학생 등록: "학생 등록" + 이름
        if (cmd.contains("등록") && (cmd.contains("학생") || cmd.contains("신규"))) {
            Map<String, Object> params = new LinkedHashMap<>();
            String name = extractName(cmd);
            if (name != null) {
                params.put("name", name);
            }
            return ParseResult.success("registerStudent", params);
        }
        // 2. 반 배정: "배정" (+ 학생ID·반ID)
        if (cmd.contains("배정")) {
            Map<String, Object> params = extractStudentAndClass(cmd);
            return ParseResult.success("assignStudentToClass", params);
        }
        // 3. 결석 조회: "결석"
        if (cmd.contains("결석")) {
            Map<String, Object> params = new LinkedHashMap<>();
            String date = extractDateWord(cmd);
            if (date != null) {
                params.put("date", date);
            }
            return ParseResult.success("getAbsentStudents", params);
        }
        // 4. 숙제 미제출: "숙제" + "미제출"/"미완료"/"안 낸" 등
        if (cmd.contains("숙제") && (cmd.contains("미제출") || cmd.contains("미완료")
                || isUnsubmitted(cmd))) {
            Map<String, Object> params = new LinkedHashMap<>();
            Long classId = extractClassId(cmd);
            if (classId != null) {
                params.put("classId", classId);
            } else {
                String className = extractClassName(cmd);
                if (className != null) {
                    params.put("className", className);
                }
            }
            Matcher hm = HOMEWORK_KW.matcher(cmd);
            if (hm.find()) {
                params.put("homeworkId", Long.parseLong(hm.group(1)));
            }
            return ParseResult.success("getHomeworkIncomplete", params);
        }
        // 5. 공지 초안 작성: "공지" + "작성"/"초안"
        if (cmd.contains("공지") && (cmd.contains("작성") || cmd.contains("초안"))) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("target", extractTarget(cmd));
            String title = extractTitle(cmd);
            if (title != null) {
                params.put("title", title);
            }
            String content = extractContent(cmd);
            if (content != null) {
                params.put("content", content);
            }
            return ParseResult.success("createNoticeDraft", params);
        }
        // 6. 성적 분석: "성적" + "분석"/"변화"/"추이"
        if (cmd.contains("성적") && (cmd.contains("분석") || cmd.contains("변화") || cmd.contains("추이"))) {
            Map<String, Object> params = new LinkedHashMap<>();
            Long studentId = extractStudentId(cmd);
            if (studentId != null) {
                params.put("studentId", studentId);
            }
            extractDateRange(cmd, params);
            return ParseResult.success("analyzeGradeChanges", params);
        }
        // 7. 운영 보고서: "보고서" + "운영"/"현황"
        if (cmd.contains("보고서") && (cmd.contains("운영") || cmd.contains("현황"))) {
            Map<String, Object> params = new LinkedHashMap<>();
            extractDateRange(cmd, params);
            Pattern recentDays = Pattern.compile("최근\\s*(\\d+)\\s*일");
            Matcher rm = recentDays.matcher(cmd);
            if (rm.find()) {
                int n = Integer.parseInt(rm.group(1));
                params.put("startDate", ReaTime.today().minusDays(n - 1).toString());
                params.put("endDate", ReaTime.today().toString());
            }
            return ParseResult.success("generateOperationReport", params);
        }
        // 8. 학생 목록: "학생" + ("목록"/"명단"/"리스트"/"조회"/"보여")
        //    다른 규칙(결석·숙제·성적 등) 뒤에 둔다 — "결석 학생", "학생 성적"이 먼저 매칭되도록.
        if (cmd.contains("학생") && (cmd.contains("목록") || cmd.contains("명단")
                || cmd.contains("리스트") || cmd.contains("조회") || cmd.contains("보여"))) {
            Map<String, Object> params = new LinkedHashMap<>();
            Long classId = extractClassId(cmd);
            if (classId != null) {
                params.put("classId", classId);
            } else {
                String className = extractClassName(cmd);
                if (className != null) {
                    params.put("className", className);
                }
            }
            return ParseResult.success("getStudents", params);
        }
        return null;
    }

    /** 명령에서 제안할 후보 도구 목록 (키워드 겹침 기반) */
    private List<String> suggest(String cmd) {
        List<String> candidates = new ArrayList<>();
        addIf(candidates, cmd, "registerStudent", "등록", "학생");
        addIf(candidates, cmd, "assignStudentToClass", "배정");
        addIf(candidates, cmd, "getAbsentStudents", "결석");
        addIf(candidates, cmd, "getHomeworkIncomplete", "숙제");
        addIf(candidates, cmd, "getStudents", "목록", "명단");
        addIf(candidates, cmd, "createNoticeDraft", "공지");
        addIf(candidates, cmd, "analyzeGradeChanges", "성적");
        addIf(candidates, cmd, "generateOperationReport", "보고서", "운영");
        return candidates;
    }

    private void addIf(List<String> out, String cmd, String tool, String... keywords) {
        for (String kw : keywords) {
            if (cmd.contains(kw)) {
                out.add(tool);
                return;
            }
        }
    }

    /** "숙제 안 낸/못 낸" 변형 표현 감지 (공백 제거 후 검사, "안내" 오탐 방지) */
    private boolean isUnsubmitted(String cmd) {
        String compact = cmd.replaceAll("\\s+", "");
        return compact.contains("안낸") || compact.contains("못낸");
    }

    /** "영어반" 같은 이름형 반 추출 (숫자형 "2반"은 extractClassId 경로이므로 제외) */
    private String extractClassName(String cmd) {
        Matcher m = CLASS_NAME_KW.matcher(cmd);
        while (m.find()) {
            String name = m.group(1) + "반";
            if (!name.matches(".*\\d.*")) {
                return name;
            }
        }
        return null;
    }

    // ===== 추출 헬퍼 =====

    /** 이름 추출: 따옴표 > "이름: X" 패턴 > "학생 등록 김철수" 패턴 */
    private String extractName(String cmd) {
        Matcher quoted = QUOTED_PATTERN.matcher(cmd);
        if (quoted.find()) {
            return quoted.group(1).trim();
        }
        Pattern nameColon = Pattern.compile("이름\\s*[:：]\\s*(\\S+)");
        Matcher nm = nameColon.matcher(cmd);
        if (nm.find()) {
            return nm.group(1).trim();
        }
        Matcher kw = NAME_KW.matcher(cmd);
        if (kw.find()) {
            return kw.group(1) != null ? kw.group(1) : kw.group(2);
        }
        return null;
    }

    /** "오늘"/"어제"/"yyyy-MM-dd" 추출 */
    private String extractDateWord(String cmd) {
        if (cmd.contains("어제")) {
            return "어제";
        }
        Matcher dm = DATE_PATTERN.matcher(cmd);
        if (dm.find()) {
            return dm.group(1);
        }
        if (cmd.contains("오늘")) {
            return "오늘";
        }
        return null;
    }

    /** "N반" → 반 ID */
    private Long extractClassId(String cmd) {
        Matcher m = CLASS_KW.matcher(cmd);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return null;
    }

    /** "학생 N" → 학생 ID */
    private Long extractStudentId(String cmd) {
        Pattern p = Pattern.compile("학생\\s*(\\d+)");
        Matcher m = p.matcher(cmd);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return null;
    }

    private Map<String, Object> extractStudentAndClass(String cmd) {
        Map<String, Object> params = new LinkedHashMap<>();
        Long studentId = extractStudentId(cmd);
        if (studentId != null) {
            params.put("studentId", studentId);
        }
        Long classId = extractClassId(cmd);
        if (classId != null) {
            params.put("classId", classId);
        }
        // "학생 3을 2반에 배정" 형태에서 순서대로 두 숫자가 나오면 앞=학생, 뒤=반
        if (!params.containsKey("studentId") || !params.containsKey("classId")) {
            Matcher m = ID_PATTERN.matcher(cmd);
            List<Long> ids = new ArrayList<>();
            while (m.find()) {
                ids.add(Long.parseLong(m.group(1)));
            }
            if (ids.size() >= 2) {
                params.putIfAbsent("studentId", ids.get(0));
                params.putIfAbsent("classId", ids.get(1));
            }
        }
        return params;
    }

    /** 공지 대상: "전체" → ALL, "N반" → 반 ID */
    private String extractTarget(String cmd) {
        if (cmd.contains("전체")) {
            return "ALL";
        }
        Long classId = extractClassId(cmd);
        return classId != null ? String.valueOf(classId) : "ALL";
    }

    /** 제목 추출: 따옴표 > "제목: X" */
    private String extractTitle(String cmd) {
        Matcher quoted = QUOTED_PATTERN.matcher(cmd);
        if (quoted.find()) {
            return quoted.group(1).trim();
        }
        Pattern p = Pattern.compile("제목\\s*[:：]\\s*([^,]+?)(?:\\s+내용|$)");
        Matcher m = p.matcher(cmd);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /** 내용 추출: "내용: X" */
    private String extractContent(String cmd) {
        Pattern p = Pattern.compile("내용\\s*[:：]\\s*(.+)$");
        Matcher m = p.matcher(cmd);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /** "yyyy-MM-dd ~ yyyy-MM-dd" → startDate/endDate */
    private void extractDateRange(String cmd, Map<String, Object> params) {
        Matcher m = DATE_PATTERN.matcher(cmd);
        List<String> dates = new ArrayList<>();
        while (m.find()) {
            dates.add(m.group(1));
        }
        if (dates.size() >= 2) {
            params.put("startDate", dates.get(0));
            params.put("endDate", dates.get(1));
        } else if (dates.size() == 1) {
            params.put("endDate", dates.get(0));
        }
    }
}
