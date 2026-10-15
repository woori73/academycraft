package com.rea.academy.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 상담 관리 DTO 모음.
 * 화면-기능 분리: Service 입출력용. UI 프레임워크 의존 없음.
 */
public class ConsultationDto {

    // ===== 스레드 생성 =====

    /**
     * 스레드 생성 요청.
     * - INQUIRY(학부모 문의): studentId 또는 authKey, title 필수. content가 있으면 첫 문의 메시지로 저장.
     * - NEWS(아이소식, 학원→학부모): studentId(대상), authorMemberId(원장·강사 작성자), title, content 필수.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateRequest {
        private Long studentId;
        private String authKey;
        private String type;
        private String title;
        private String content;
        private Long authorMemberId;
        private String attachmentFileName;
        private String attachmentFileKey;
    }

    /** 스레드 생성 응답: 스레드 + 첫 메시지 + 알림 발송 결과 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateResponse {
        private DetailResponse consultation;
        private Long firstMessageId;
        private int notificationSentCount;
        private int notificationFailedCount;
        private int notificationSkippedCount;
        /** 알림 발송 자체가 실패한 경우 (실패 격리: 스레드 등록은 유지) */
        private String notificationError;
    }

    // ===== 조회 =====

    /** 스레드 목록 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ListResponse {
        private Long id;
        private Long academyId;
        private Long studentId;
        private String studentName;
        /** INQUIRY(문의) / NEWS(아이소식) */
        private String type;
        private String typeLabel;
        /** OPEN(진행중) / ANSWERED(답변완료) / CLOSED(종료) */
        private String status;
        private String statusLabel;
        private String title;
        private long messageCount;
        /** 마지막 메시지 시각 (메시지 없으면 null) */
        private LocalDateTime lastMessageAt;
        private LocalDateTime createdAt;
    }

    /** 스레드 상세 응답: 스레드 + 메시지 목록 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DetailResponse {
        private Long id;
        private Long academyId;
        private Long studentId;
        private String studentName;
        private String type;
        private String typeLabel;
        private String status;
        private String statusLabel;
        private String title;
        private long messageCount;
        /** 조회자 기준 미읽음 건수 (학부모: 학원 메시지 / 관리자: 학부모 메시지) */
        private long unreadCount;
        private List<MessageResponse> messages;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // ===== 메시지 =====

    /**
     * 메시지 작성 요청.
     * - 학부모: studentId 또는 authKey (해당 스레드의 학생이어야 함)
     * - 학원(원장·강사): memberId
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MessageRequest {
        private String content;
        private String attachmentFileName;
        private String attachmentFileKey;
        private Long studentId;
        private String authKey;
        private Long memberId;
    }

    /** 메시지 작성 응답: 메시지 + 학원 발신 시 학부모 알림 발송 결과 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateMessageResponse {
        private MessageResponse message;
        private int notificationSentCount;
        private int notificationFailedCount;
        private int notificationSkippedCount;
        /** 알림 발송 자체가 실패한 경우 (실패 격리: 메시지 저장은 유지) */
        private String notificationError;
    }

    /** 메시지 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MessageResponse {
        private Long id;
        private Long consultationId;
        /** PARENT(학부모) / STAFF(학원) */
        private String senderKind;
        private String senderName;
        private String content;
        private String attachmentFileName;
        private String attachmentFileKey;
        private boolean read;
        private LocalDateTime readAt;
        private LocalDateTime createdAt;
    }

    // ===== 종료 =====

    /**
     * 스레드 종료 요청.
     * - 학원(원장·강사): memberId
     * - 학부모: studentId 또는 authKey (본인 스레드만)
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CloseRequest {
        private Long studentId;
        private String authKey;
        private Long memberId;
    }

    // ===== 읽음 처리 =====

    /**
     * 메시지 읽음 처리 요청.
     * - 학부모: studentId 또는 authKey (본인 스레드의 상대방 메시지만)
     * - 학원(원장·강사): memberId
     * 본인이 작성한 메시지는 읽음 처리할 수 없다.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ReadRequest {
        private Long studentId;
        private String authKey;
        private Long memberId;
    }

    /** 읽음 처리 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ReadResponse {
        private Long consultationId;
        private Long messageId;
        private String readerName;
        private LocalDateTime readAt;
        /** 이미 읽음 처리된 메시지였던 경우 true */
        private boolean alreadyRead;
    }
}
