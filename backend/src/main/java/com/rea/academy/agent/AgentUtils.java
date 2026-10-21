package com.rea.academy.agent;

import com.rea.academy.common.ReaTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Agent 도구·파서 공통 유틸리티.
 */
public final class AgentUtils {

    private AgentUtils() {
    }

    /** 입력에서 필수 문자열 파라미터 조회 */
    public static String requiredString(Map<String, Object> input, String key, String displayName) {
        Object v = input.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException(displayName + "(은)는 필수입니다.");
        }
        return v.toString().trim();
    }

    /** 입력에서 선택 문자열 파라미터 조회 */
    public static String optionalString(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        return v.toString().trim();
    }

    /** 입력에서 필수 Long 파라미터 조회 (숫자·숫자 문자열 모두 허용) */
    public static Long requiredLong(Map<String, Object> input, String key, String displayName) {
        Object v = input.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException(displayName + "(은)는 필수입니다.");
        }
        return toLong(v, displayName);
    }

    /** 입력에서 선택 Long 파라미터 조회 */
    public static Long optionalLong(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        return toLong(v, key);
    }

    public static Long toLong(Object v, String displayName) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(displayName + "은(는) 숫자여야 합니다: " + v);
        }
    }

    /**
     * 날짜 파싱: "오늘", "어제", "내일", "yyyy-MM-dd" 허용.
     * 빈 값이면 defaultValue 사용.
     */
    public static LocalDate parseDate(Object raw, LocalDate defaultValue) {
        if (raw == null || raw.toString().isBlank()) {
            return defaultValue;
        }
        String s = raw.toString().trim();
        return switch (s) {
            case "오늘" -> ReaTime.today();
            case "어제" -> ReaTime.today().minusDays(1);
            case "내일" -> ReaTime.today().plusDays(1);
            default -> {
                try {
                    yield LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(
                            "날짜 형식이 올바르지 않습니다 (오늘/어제/yyyy-MM-dd): " + s);
                }
            }
        };
    }
}
