package com.rea.academy.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 학습 관리(숙제·진도·성적·리포트) DTO 모음.
 * 화면-기능 분리: Service 입출력용. UI 프레임워크 의존 없음.
 */
public class LearningDto {

    // ===== 숙제 등록 =====

    /**
     * 숙제 등록 요청.
     * - classId(반별) / studentId(학생별) 중 최소 하나 필수. 둘 다 지정 가능.
     * - dueDate: yyyy-MM-dd
     * - questionRefs: 내신Wiki 문항 참조 (연동 구조용, 선택)
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HomeworkCreateRequest {
        private Long classId;
        private Long studentId;
        private String title;
        private String content;
        private String dueDate;
        private List<QuestionRefRequest> questionRefs;
    }

    /** 내신Wiki 문항 참조 요청 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class QuestionRefRequest {
        private Long questionId;
        private String source;
    }

    /** 숙제 등록 응답: 숙제 + 알림 발송 결과 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HomeworkCreateResponse {
        private HomeworkResponse homework;
        private int notificationRequestedCount;
        private int notificationSentCount;
        private int notificationFailedCount;
        private int notificationSkippedCount;
        /** 알림 발송 자체가 실패한 경우 (실패 격리: 숙제 등록은 유지) */
        private String notificationError;
    }

    /** 숙제 응답 (목록·상세 공용). studentId 조회 시 해당 학생의 검사 상태 포함. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HomeworkResponse {
        private Long id;
        private Long academyId;
        private Long classId;
        private String className;
        private Long studentId;
        private String studentName;
        private String title;
        private String content;
        private String dueDate;
        private int questionRefCount;
        /** COMPLETED(완료) / INCOMPLETE(미완료) / null(미검사) — studentId 조회 시에만 채워짐 */
        private String checkStatus;
        private String checkStatusLabel;
        private Integer checkScore;
        private String checkComment;
        private LocalDateTime checkedAt;
        private LocalDateTime createdAt;
    }

    // ===== 숙제 검사 =====

    /**
     * 숙제 검사 요청.
     * - status: COMPLETED(완료) / INCOMPLETE(미완료)
     * - score: 선택 (0 이상)
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HomeworkCheckRequest {
        private Long studentId;
        private String status;
        private Integer score;
        private String comment;
    }

    /** 숙제 검사 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HomeworkCheckResponse {
        private Long id;
        private Long homeworkId;
        private String homeworkTitle;
        private Long studentId;
        private String studentName;
        private String status;
        private String statusLabel;
        private Integer score;
        private String comment;
        private LocalDateTime checkedAt;
    }

    // ===== 진도 =====

    /**
     * 진도 기록 요청.
     * - unit(단원) 필수, progressRate 0~100 필수
     * - recordedDate: yyyy-MM-dd (선택, 기본값=오늘)
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ProgressRecordRequest {
        private Long studentId;
        private String unit;
        private Integer progressRate;
        private String memo;
        private String recordedDate;
    }

    /** 진도 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ProgressResponse {
        private Long id;
        private Long studentId;
        private String studentName;
        private String unit;
        private Integer progressRate;
        private String memo;
        private LocalDate recordedDate;
        private LocalDateTime createdAt;
    }

    // ===== 성적 =====

    /**
     * 성적 등록 요청.
     * - examName(시험명)·subject(과목)·score(점수)·fullScore(만점) 필수
     * - 0 <= score <= fullScore, fullScore >= 1
     * - examDate: yyyy-MM-dd (선택, 기본값=오늘)
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class GradeCreateRequest {
        private Long studentId;
        private String examName;
        private String subject;
        private Integer score;
        private Integer fullScore;
        private String examDate;
        private String note;
    }

    /** 성적 등록 응답: 성적 + 알림 발송 결과 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class GradeCreateResponse {
        private GradeResponse grade;
        private int notificationSentCount;
        private int notificationFailedCount;
        private int notificationSkippedCount;
        /** 알림 발송 자체가 실패한 경우 (실패 격리: 성적 등록은 유지) */
        private String notificationError;
    }

    /** 성적 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class GradeResponse {
        private Long id;
        private Long studentId;
        private String studentName;
        private String examName;
        private String subject;
        private Integer score;
        private Integer fullScore;
        /** 백분율 (소수점 1자리) */
        private Double rate;
        private LocalDate examDate;
        private String note;
        private LocalDateTime createdAt;
    }

    // ===== 학습 리포트 =====

    /** 과목별 성적 평균 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SubjectAverage {
        private String subject;
        private long count;
        private Double avgRate;
    }

    /** 성적 요약 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class GradeSummary {
        private long gradeCount;
        /** 전체 평균 백분율 (소수점 1자리, 성적 없으면 null) */
        private Double avgRate;
        private List<SubjectAverage> subjectAverages;
        private String latestExamName;
    }

    /** 숙제 요약 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HomeworkSummary {
        /** 배정된 숙제 수 (반별+개별) */
        private long assignedCount;
        /** 검사 완료된 숙제 수 */
        private long checkedCount;
        /** 완료 판정 수 */
        private long completedCount;
        /** 미완료 판정 수 */
        private long incompleteCount;
        /** 미검사 수 */
        private long uncheckedCount;
    }

    /** 출결 요약 (최근 30일) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AttendanceSummary {
        private int periodDays;
        private long checkInDays;
        private long absentDays;
        private long lateDays;
        private long earlyLeaveDays;
    }

    /** 진도 요약 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ProgressSummary {
        private long recordCount;
        /** 최근 진도 기록 최대 5건 */
        private List<ProgressResponse> latest;
    }

    /** 학습 리포트 응답 (RPT-01 데이터 API) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ReportResponse {
        private Long academyId;
        private Long studentId;
        private String studentName;
        private GradeSummary gradeSummary;
        private HomeworkSummary homeworkSummary;
        private ProgressSummary progressSummary;
        private AttendanceSummary attendanceSummary;
        private LocalDateTime generatedAt;
    }
}
