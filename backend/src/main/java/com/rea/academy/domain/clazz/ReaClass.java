package com.rea.academy.domain.clazz;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * REA 반(학급) 엔티티.
 * 비투 공개 동작 참고: 반 이름 지정 + 반 소속 학생 목록 + 반별 시간표.
 * 모든 데이터에 academyId를 포함 (학원별 격리).
 *
 * 화면-기능 분리: 이 엔티티는 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_classes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 반 이름 (필수) */
    @Column(nullable = false, length = 50)
    private String name;

    /** 반 설명 (선택) */
    @Column(length = 500)
    private String description;

    /** 담당 강사 ID (rea_members.id). 필수. TEACHER 또는 ACADEMY_OWNER 역할 */
    @Column(nullable = false)
    private Long teacherId;

    /**
     * 수업 요일. 콤마 구분 문자열로 저장 (예: "MON,WED,FRI").
     * 선택 값. 비어 있으면 시간표 미지정.
     */
    @Column(length = 30)
    private String weekdays;

    /** 수업 시작 시각 (선택) */
    private LocalTime startTime;

    /** 수업 종료 시각 (선택) */
    private LocalTime endTime;

    /** 강의실 (선택) */
    @Column(length = 50)
    private String room;

    /** 정원 (선택, null이면 무제한) */
    private Integer capacity;

    /** 활성 여부 (종료 시 false) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 반 정보 수정 */
    public void update(String name, String description, Long teacherId,
                       String weekdays, LocalTime startTime, LocalTime endTime,
                       String room, Integer capacity) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (teacherId != null) this.teacherId = teacherId;
        if (weekdays != null) this.weekdays = weekdays.isBlank() ? null : weekdays;
        this.startTime = startTime;
        this.endTime = endTime;
        if (room != null) this.room = room.isBlank() ? null : room;
        if (capacity != null) this.capacity = capacity;
    }

    /** 반 종료 (비활성) */
    public void close() {
        this.active = false;
    }
}
