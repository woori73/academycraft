package com.rea.academy.domain.admin;

import com.rea.academy.common.ReaTime;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 학원 기본정보 엔티티 (8단계 학원 관리).
 * - id = academyId (멀티테넌트 키 그 자체. 자동 생성이 아니라 명시적 지정)
 * - parentAcademyId: 본원·지점 관계. null이면 본원(또는 독립 학원), 값이 있으면 해당 학원의 지점
 * - 지점 등록 시 AdminService가 새 academyId를 발급(max+1)하여 행을 생성한다
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_academies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaAcademy {

    /**
     * 학원 ID = 멀티테넌트 키.
     * 다른 모든 엔티티의 academyId와 같은 값 체계. @GeneratedValue를 사용하지 않는다.
     */
    @Id
    private Long id;

    /** 학원 이름 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 대표 전화번호 (선택) */
    @Column(length = 20)
    private String phone;

    /** 주소 (선택) */
    @Column(length = 200)
    private String address;

    /** 로고 파일 키 (선택, FileStorage 연동 시 사용) */
    @Column(length = 200)
    private String logoKey;

    /** 메모 (선택) */
    @Column(length = 500)
    private String memo;

    /** 본원 학원 ID (null이면 본원/독립 학원) */
    private Long parentAcademyId;

    /** 활성 여부 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = ReaTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = ReaTime.now();
    }

    /**
     * 학원 정보 수정. null인 항목은 변경하지 않는다.
     */
    public void update(String name, String phone, String address, String logoKey, String memo) {
        if (name != null) this.name = name;
        if (phone != null) this.phone = phone;
        if (address != null) this.address = address;
        if (logoKey != null) this.logoKey = logoKey;
        if (memo != null) this.memo = memo;
    }

    /** 지점 여부 */
    public boolean isBranch() {
        return parentAcademyId != null;
    }
}
