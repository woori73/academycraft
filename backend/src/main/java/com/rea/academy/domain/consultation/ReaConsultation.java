package com.rea.academy.domain.consultation;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 상담 스레드 엔티티 (문의·아이소식 1:1 대화).
 * - INQUIRY: 학부모가 시작한 1:1 문의
 * - NEWS: 학원(원장·강사)이 특정 학부모에게 보낸 1:1 소식
 *
 * 학부모는 학생 인증키로 로그인하므로 스레드의 학부모 측은 studentId로 식별한다.
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_consultations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaConsultation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 대상 학생 ID (학부모 = 학생 인증키 로그인 사용자) */
    @Column(nullable = false)
    private Long studentId;

    /** 스레드 생성 당시 학생명 스냅샷 */
    @Column(length = 50)
    private String studentName;

    /** 상담 종류: INQUIRY(문의) / NEWS(아이소식) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsultationType type;

    /** 상담 상태: OPEN(진행중) / ANSWERED(답변완료) / CLOSED(종료) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConsultationStatus status = ConsultationStatus.OPEN;

    /** 스레드 제목 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 활성 여부 (비활성 스레드는 목록·상세에서 제외) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 학원(원장·강사) 답변 등록 → 답변완료 */
    public void markAnswered() {
        this.status = ConsultationStatus.ANSWERED;
    }

    /** 답변완료 상태에서 학부모가 추가 문의 → 진행중으로 복귀 */
    public void reopen() {
        this.status = ConsultationStatus.OPEN;
    }

    /** 상담 종료 */
    public void close() {
        this.status = ConsultationStatus.CLOSED;
    }

    public void deactivate() {
        this.active = false;
    }
}
