package com.rea.academy.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공지 관리 DTO 모음.
 * 화면-기능 분리: Service 입출력용. UI 프레임워크 의존 없음.
 */
public class NoticeDto {

    /** 공지 대상 구분 */
    public static final String SCOPE_ALL = "ALL";     // 전체공지 (classId=null)
    public static final String SCOPE_CLASS = "CLASS"; // 반소식 (classId 지정)

    // ===== 작성 =====

    /** 작성 요청: {classId(선택, null이면 전체공지), title, content, authorId(선택), 첨부파일명·키(선택)} */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateRequest {
        private Long classId;
        private String title;
        private String content;
        private Long authorId;
        private String attachmentFileName;
        private String attachmentFileKey;
    }

    /** 수정 요청: null인 항목은 변경하지 않는다. changeToAll=true면 전체공지로 전환. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateRequest {
        private String title;
        private String content;
        private Long classId;
        private Boolean changeToAll;
        private String attachmentFileName;
        private String attachmentFileKey;
    }

    // ===== 조회 =====

    /** 목록 응답 (contentPreview는 앞 100자) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ListResponse {
        private Long id;
        private Long academyId;
        private Long classId;
        private String className;
        /** ALL(전체공지) / CLASS(반소식) */
        private String scope;
        private String title;
        private String contentPreview;
        private String authorName;
        /** 조회수: 관리자 조회에서만 값이 채워진다. 학부모 조회에서는 null */
        private Integer viewCount;
        /** 읽음 확인 인원수 */
        private long readCount;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 상세 응답: 목록 항목 + 본문 + 첨부 + 본인 읽음 여부 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DetailResponse {
        private Long id;
        private Long academyId;
        private Long classId;
        private String className;
        private String scope;
        private String title;
        private String content;
        private String authorName;
        private String attachmentFileName;
        private String attachmentFileKey;
        /** 조회수: 관리자 조회에서만 값이 채워진다. 학부모 조회에서는 null */
        private Integer viewCount;
        private long readCount;
        /** 본인(학생) 읽음 여부 (학부모 조회에서만 채워짐) */
        private Boolean myRead;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /** 작성 응답: 공지 + 알림 발송 결과 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateResponse {
        private DetailResponse notice;
        private int notificationSentCount;
        private int notificationFailedCount;
        private int notificationSkippedCount;
        /** 알림 발송 자체가 실패한 경우 (실패 격리: 공지 등록은 유지) */
        private String notificationError;
    }

    // ===== 읽음 확인 =====

    /** 읽음 확인 요청: {studentId 또는 authKey} */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ReadRequest {
        private Long studentId;
        private String authKey;
    }

    /** 읽음 확인 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ReadResponse {
        private Long noticeId;
        private Long studentId;
        private String studentName;
        private LocalDateTime readAt;
        /** 이미 읽음 처리된 기록이 있었던 경우 true */
        private boolean alreadyRead;
    }

    /** 확인자(읽은 사람) 목록 항목 — 관리자용 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ReaderResponse {
        private Long studentId;
        private String studentName;
        private LocalDateTime readAt;
    }
}
