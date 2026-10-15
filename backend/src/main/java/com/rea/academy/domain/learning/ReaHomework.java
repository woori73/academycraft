package com.rea.academy.domain.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 숙제(과제) 엔티티.
 * - 반별 숙제: classId 지정 (해당 반 소속 학생 전체가 대상)
 * - 학생별 숙제: studentId 지정 (개별 학생이 대상)
 * - classId/studentId 중 최소 하나는 필수 (둘 다 지정 가능 — 반 숙제 + 개별 보충 형태)
 * - 내신Wiki 문항 참조: questionRefs (연동 구조용. 실제 조회는 향후 {@link WikiIntegrationPort})
 *
 * 비투 공개 동작 참고: 숙제 등록 → 학부모에게 숙제 안내 → 강사가 검사 결과 입력.
 * 모든 데이터에 academyId를 포함 (학원별 격리).
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_homework")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaHomework {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 대상 반 ID (rea_classes.id, 반별 숙제인 경우) */
    private Long classId;

    /** 대상 반명 스냅샷 (등록 당시) */
    @Column(length = 100)
    private String className;

    /** 대상 학생 ID (rea_students.id, 학생별 숙제인 경우) */
    private Long studentId;

    /** 대상 학생명 스냅샷 (등록 당시) */
    @Column(length = 50)
    private String studentName;

    /** 숙제 제목 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 숙제 내용 */
    @Column(nullable = false, length = 2000)
    private String content;

    /** 마감일 */
    @Column(nullable = false)
    private LocalDate dueDate;

    /** 내신Wiki 문항 참조 목록 (숙제 출제용. 연동 구조 스텁) */
    @ElementCollection
    @CollectionTable(name = "rea_homework_question_refs",
            joinColumns = @JoinColumn(name = "homework_id"))
    @Builder.Default
    private List<WikiQuestionRef> questionRefs = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
