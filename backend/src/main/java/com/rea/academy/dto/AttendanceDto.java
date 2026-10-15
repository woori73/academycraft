package com.rea.academy.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

/**
 * 출결 관리 DTO.
 * 시각은 "HH:mm" (설정), 발생 일시는 "yyyy-MM-dd'T'HH:mm" (ISO_LOCAL_DATE_TIME) 문자열.
 * 일자는 "yyyy-MM-dd", 월은 "yyyy-MM" 문자열.
 *
 * 화면-기능 분리: Service Layer는 DTO로 입출력. UI는 DTO를 받아 렌더링.
 */
public class AttendanceDto {

    // ===== 출결 기록 =====

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CheckRequest {
        /** 학생 ID (authKey와 둘 중 하나 필수) */
        private Long studentId;
        /** 학생 인증키 (studentId와 둘 중 하나 필수) */
        private String authKey;
        /** 출결 구분 (필수, CHECK_IN/CHECK_OUT/ABSENT/LATE/EARLY_LEAVE) */
        private String eventType;
        /** 발생 일시 (선택, "yyyy-MM-dd'T'HH:mm". 미지정 시 기록 시점) */
        private String occurredAt;
        /** 메모 (선택) */
        private String note;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class KeypadRequest {
        /** 학생 출석키 (= 학생 인증키) */
        private String authKey;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CheckResponse {
        private Long id;
        private Long academyId;
        private Long studentId;
        private String studentName;
        private String eventType;
        /** 자동 판별 결과 여부 (키패드 입력 시 true) */
        private Boolean autoDetected;
        private String occurredAt;
        private String source;
        private String note;
    }

    // ===== 출결 이벤트 조회 =====

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class EventResponse {
        private Long id;
        private Long academyId;
        private Long studentId;
        private String studentName;
        private Long classId;
        private String className;
        private String eventType;
        private String occurredAt;
        private String source;
        private String note;
        private Boolean corrected;
        private List<CorrectionResponse> corrections;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CorrectionResponse {
        private Long id;
        private String beforeType;
        private String afterType;
        private String beforeOccurredAt;
        private String afterOccurredAt;
        private String beforeNote;
        private String afterNote;
        private String reason;
        private String correctedAt;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CorrectRequest {
        /** 정정 후 출결 구분 (선택. 미지정 시 기존 유지) */
        private String eventType;
        /** 정정 후 발생 일시 (선택, "yyyy-MM-dd'T'HH:mm". 미지정 시 기존 유지) */
        private String occurredAt;
        /** 정정 후 메모 (선택) */
        private String note;
        /** 정정 사유 (필수) */
        private String reason;
    }

    // ===== 반별 출결부 =====

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RosterRequest {
        private Long classId;
        /** 조회 일자 ("yyyy-MM-dd") */
        private String date;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RosterEntry {
        private Long studentId;
        private String studentName;
        private String grade;
        /**
         * 당일 출결 상태: PRESENT(등원) / CHECKED_OUT(하원완료) / ABSENT(결석)
         * / LATE(지각) / EARLY_LEAVE(조퇴) / NONE(미기록)
         */
        private String status;
        private String checkInAt;
        private String checkOutAt;
        private List<EventResponse> events;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RosterResponse {
        private Long classId;
        private String className;
        private String date;
        private List<RosterEntry> entries;
    }

    // ===== 월별 현황 =====

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MonthlyStudent {
        private Long studentId;
        private String studentName;
        private int presentDays;
        private int absentDays;
        private int lateDays;
        private int earlyLeaveDays;
        /** 일자(1~31) → 상태 코드 (IN / OUT / ABSENT / LATE / EARLY_LEAVE / NONE) */
        private Map<Integer, String> dailyStatus;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MonthlyResponse {
        private Long classId;
        private String className;
        /** "yyyy-MM" */
        private String yearMonth;
        private int daysInMonth;
        private List<MonthlyStudent> students;
    }

    // ===== 출결 설정 =====

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SettingsRequest {
        /** 하원 가능 시작 시각 ("HH:mm", 빈 문자열이면 해제) */
        private String checkOutEarliestTime;
        /** 하원 가능 종료 시각 ("HH:mm", 빈 문자열이면 해제) */
        private String checkOutLatestTime;
        /** 출결 사진 전송 여부 (null이면 변경 없음) */
        private Boolean photoEnabled;
        /** 등원 알림 문구 (null이면 변경 없음, {name} 자리표시자) */
        private String checkInMessage;
        /** 하원 알림 문구 (null이면 변경 없음, {name} 자리표시자) */
        private String checkOutMessage;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SettingsResponse {
        private Long academyId;
        private String checkOutEarliestTime;
        private String checkOutLatestTime;
        private Boolean photoEnabled;
        private String checkInMessage;
        private String checkOutMessage;
    }
}
