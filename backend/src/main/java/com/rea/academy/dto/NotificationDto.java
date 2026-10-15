package com.rea.academy.dto;

import lombok.*;

import java.util.List;

/**
 * 알림 DTO 모음.
 * 화면-기능 분리: Service 입출력용. UI 프레임워크 의존 없음.
 */
public class NotificationDto {

    // ===== 메시지 발송 =====

    /** 발송 요청: {targetType: INDIVIDUAL/CLASS/ALL, targetIds[], title, content} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendRequest {
        private String targetType;
        private List<Long> targetIds;
        private String title;
        private String content;
    }

    /** 발송 결과: 요청 수·성공·실패·설정OFF생략 건수 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendResponse {
        private int requestedCount;
        private int sentCount;
        private int failedCount;
        private int skippedCount;
        private List<Long> notificationIds;
    }

    // ===== 알림 목록 (수신자) =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationResponse {
        private Long id;
        private Long academyId;
        private Long recipientStudentId;
        private String recipientName;
        private String channel;
        private String type;
        private String typeLabel;
        private String title;
        private String content;
        private String status;
        private boolean read;
        private String createdAt;
    }

    // ===== 발송 이력 (관리자) =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryResponse {
        private Long id;
        private Long academyId;
        private Long recipientStudentId;
        private String recipientName;
        private String recipientPhone;
        private String channel;
        private String type;
        private String typeLabel;
        private String title;
        private String content;
        private String status;
        private String failReason;
        private int retryCount;
        private boolean read;
        private String createdAt;
    }

    // ===== 읽음 처리 =====

    /** 읽음 요청: {studentId 또는 authKey} — 본인 알림만 읽음 처리 가능 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReadRequest {
        private Long studentId;
        private String authKey;
    }

    // ===== 알림 설정 =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettingUpdate {
        private String type;
        private Boolean enabled;
    }

    /** 설정 변경 요청: {studentId 또는 authKey, settings: [{type, enabled}]} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettingsRequest {
        private Long studentId;
        private String authKey;
        private List<SettingUpdate> settings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettingEntry {
        private String type;
        private String typeLabel;
        private boolean enabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettingsResponse {
        private Long academyId;
        private Long studentId;
        private String studentName;
        private List<SettingEntry> settings;
    }
}
