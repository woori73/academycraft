package com.rea.academy.domain.admin;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 변경 이력(감사 로그) 엔티티 (8단계 학원 관리).
 * - 주요 변경 사항을 불변(append-only) 기록으로 남긴다. 수정·삭제 API 없음
 * - actorId/actorName: 행위자(원장·강사) 정보. 기존 서비스 경유 시 행위자를 알 수 없어 null 가능
 * - targetName: 조회 시점과 무관하게 당시 이름을 보여주기 위한 스냅샷
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 행위 종류 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditActionType actionType;

    /** 변경 대상 종류: 학생/반/출결/공지/회원/학원 */
    @Column(length = 20)
    private String targetType;

    /** 변경 대상 ID */
    private Long targetId;

    /** 변경 대상 이름 스냅샷 */
    @Column(length = 100)
    private String targetName;

    /** 행위자 회원 ID (시스템/미상 시 null) */
    private Long actorId;

    /** 행위자 이름 (시스템/미상 시 null) */
    @Column(length = 50)
    private String actorName;

    /** 상세 설명 */
    @Column(length = 2000)
    private String detail;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
