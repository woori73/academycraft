package com.rea.academy.dto;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 운영 관리(일정·강사 담당·통합 검색·통계·CSV) DTO 모음 (7단계).
 * 화면-기능 분리: Service 입출력용. UI 프레임워크 의존 없음.
 */
public class OpsDto {

    // ===== 일정 =====

    /**
     * 일정 등록 요청.
     * - title(제목)·date(yyyy-MM-dd) 필수
     * - startTime/endTime: HH:mm (선택). endTime은 startTime보다 늦어야 함
     * - targetType: ALL(학원 전체, 기본) / CLASS(반별)
     * - classId: CLASS인 경우 필수 (해당 학원 반이어야 함)
     * - consultationId: 상담 일정 연결 (선택, 해당 학원 상담 스레드여야 함)
     * - memo: 선택
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ScheduleCreateRequest {
        private String title;
        private String date;
        private String startTime;
        private String endTime;
        private String targetType;
        private Long classId;
        private Long consultationId;
        private String memo;
    }

    /**
     * 일정 수정 요청 (PUT). null인 항목은 변경하지 않는다.
     * - targetType을 ALL로 바꾸면 classId는 null로 초기화된다.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ScheduleUpdateRequest {
        private String title;
        private String date;
        private String startTime;
        private String endTime;
        private String targetType;
        private Long classId;
        private Long consultationId;
        private String memo;
    }

    /** 일정 응답 (월별 조회·등록·수정 공용) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ScheduleResponse {
        private Long id;
        private Long academyId;
        private String title;
        private LocalDate date;
        private String startTime;
        private String endTime;
        /** ALL(학원 전체) / CLASS(반별) */
        private String targetType;
        private String targetTypeLabel;
        private Long classId;
        private String className;
        /** 연결된 상담 스레드 (상담 일정인 경우) */
        private Long consultationId;
        private String consultationTitle;
        private String memo;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    // ===== 강사 담당 =====

    /** 강사 담당 반 요약 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TeacherClassSummary {
        private Long classId;
        private String className;
        private long studentCount;
    }

    /** 강사 담당 현황 응답: 담당 반 + 담당 업무 메모 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TeacherAssignmentResponse {
        private Long teacherId;
        private String teacherName;
        /** 담당 업무 메모 (원장이 입력, 미등록 시 null) */
        private String dutyMemo;
        private List<TeacherClassSummary> classes;
        private int classCount;
        /** 담당 반 소속 활성 학생 수 합계 */
        private long totalStudentCount;
    }

    /** 담당 업무 메모 수정 요청 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DutyMemoUpdateRequest {
        private String dutyMemo;
    }

    // ===== 통합 검색 =====

    /** 학생 검색 결과 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class StudentHit {
        private Long studentId;
        private String studentName;
        private String grade;
        private String parentName;
        private String parentPhone;
    }

    /** 출결 검색 결과 (일치한 학생의 최근 기록) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AttendanceHit {
        private Long id;
        private Long studentId;
        private String studentName;
        /** 등원/하원/결석/지각/조퇴 */
        private String eventType;
        private String eventTypeLabel;
        private LocalDateTime occurredAt;
    }

    /** 성적 검색 결과 (일치한 학생의 최근 성적) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class GradeHit {
        private Long id;
        private Long studentId;
        private String studentName;
        private String examName;
        private String subject;
        private Integer score;
        private Integer fullScore;
        private LocalDate examDate;
    }

    /** 숙제 검색 결과 (일치한 학생이 대상인 숙제) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HomeworkHit {
        private Long id;
        private String title;
        private String className;
        private String studentName;
        private LocalDate dueDate;
    }

    /** 상담 검색 결과 (일치한 학생의 스레드 + 제목 일치 스레드) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ConsultationHit {
        private Long id;
        private Long studentId;
        private String studentName;
        private String type;
        private String typeLabel;
        private String status;
        private String statusLabel;
        private String title;
    }

    /** 공지 검색 결과 (제목 일치) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class NoticeHit {
        private Long id;
        private String title;
        private String scope;
        private LocalDateTime createdAt;
    }

    /** 통합 검색 응답: 학생 이름으로 출결·성적·숙제·상담·공지를 횡단 검색 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SearchResponse {
        private String query;
        private List<StudentHit> students;
        private List<AttendanceHit> attendances;
        private List<GradeHit> grades;
        private List<HomeworkHit> homework;
        private List<ConsultationHit> consultations;
        private List<NoticeHit> notices;
    }

    // ===== 통계 대시보드 =====

    /** 오늘 출결 현황 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TodayAttendanceStats {
        private LocalDate date;
        private long checkInCount;
        private long checkOutCount;
        private long absentCount;
        private long lateCount;
        private long earlyLeaveCount;
        /** 출결 기록이 있는 학생 수 (중복 제외) */
        private long studentCount;
    }

    /** 통계 대시보드 응답 (OPS-02) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DashboardStatsResponse {
        private Long academyId;
        /** 활성 학생 수 */
        private long studentCount;
        /** 활성 반 수 */
        private long classCount;
        /** 오늘 출결 현황 */
        private TodayAttendanceStats todayAttendance;
        /** 미답변(OPEN) 상담 스레드 수 */
        private long unansweredConsultationCount;
        /** 이번 달 공지 수 */
        private long monthlyNoticeCount;
        /** 집계 기준 월 (yyyy-MM) */
        private String month;
        private LocalDateTime generatedAt;
    }

    // ===== CSV 가져오기 =====

    /**
     * 학생 일괄 등록 CSV 가져오기 응답.
     * - 입력 형식: 헤더(선택) + 행. 열 순서: 이름,학년,반,학부모명,연락처
     *   예) 이름,학년,반,학부모명,연락처
     *       홍길동,고2,수학A반,홍부모,010-1234-5678
     * - 이름이 비어 있으면 해당 행은 실패. 반 이름이 학원에 없으면 학생은 등록되고 배정은 생략(경고).
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class StudentImportResponse {
        private int totalRows;
        private int successCount;
        private int failedCount;
        /** 행 단위 오류·경고 메시지 (예: "3행: 이름이 비어 있습니다") */
        private List<String> errors;
    }
}
