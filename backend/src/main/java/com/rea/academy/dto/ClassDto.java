package com.rea.academy.dto;

import lombok.*;

import java.util.List;

/**
 * 반 관리 DTO.
 * 요일은 요일명 문자열 리스트 (예: ["MON","WED","FRI"]).
 * 시각은 "HH:mm" 문자열 (예: "14:00").
 *
 * 화면-기능 분리: Service Layer는 DTO로 입출력. UI는 DTO를 받아 렌더링.
 */
public class ClassDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateRequest {
        private Long academyId;
        /** 반 이름 (필수) */
        private String name;
        private String description;
        /** 담당 강사 ID (필수, TEACHER 또는 ACADEMY_OWNER) */
        private Long teacherId;
        /** 수업 요일 (선택, 예: ["MON","WED","FRI"]) */
        private List<String> weekdays;
        /** 수업 시작 시각 (선택, "HH:mm") */
        private String startTime;
        /** 수업 종료 시각 (선택, "HH:mm") */
        private String endTime;
        private String room;
        /** 정원 (선택, null이면 무제한) */
        private Integer capacity;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateRequest {
        private String name;
        private String description;
        private Long teacherId;
        /** null이면 변경 없음. 빈 리스트면 시간표 요일 삭제. */
        private List<String> weekdays;
        /** null이면 변경 없음. 빈 문자열이면 시각 삭제. */
        private String startTime;
        private String endTime;
        private String room;
        private Integer capacity;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Response {
        private Long id;
        private Long academyId;
        private String name;
        private String description;
        private Long teacherId;
        private String teacherName;
        private List<String> weekdays;
        private String startTime;
        private String endTime;
        private String room;
        private Integer capacity;
        /** 현재 소속 학생 수 */
        private long studentCount;
        private Boolean active;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DetailResponse {
        private Long id;
        private Long academyId;
        private String name;
        private String description;
        private Long teacherId;
        private String teacherName;
        private List<String> weekdays;
        private String startTime;
        private String endTime;
        private String room;
        private Integer capacity;
        private long studentCount;
        private Boolean active;
        /** 소속 학생 목록 */
        private List<StudentDto.Response> students;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AssignRequest {
        /** 배정할 학생 ID 목록 */
        private List<Long> studentIds;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MoveRequest {
        /** 이동 대상 반 ID */
        private Long toClassId;
    }

    /**
     * 주간 시간표 항목.
     * 반의 요일·시간 정보를 일자별로 펼친 행.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TimetableEntry {
        private Long classId;
        private String className;
        /** 요일명 (예: "MON") */
        private String dayOfWeek;
        private String startTime;
        private String endTime;
        private String room;
        private String teacherName;
        private long studentCount;
    }
}
