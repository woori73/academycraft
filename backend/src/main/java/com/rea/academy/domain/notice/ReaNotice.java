package com.rea.academy.domain.notice;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 공지 엔티티.
 * classId가 null이면 학원 전체공지, 값이 있으면 해당 반의 반소식.
 * 삭제는 비활성(active=false)으로 처리한다.
 * 조회수(viewCount)는 상세 조회 시마다 1씩 증가하며, 관리자(원장·강사)에게만 표시한다.
 * 첨부파일은 4단계에서 메타데이터(파일명·키)만 저장하고 실제 업로드는 하지 않는다.
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 대상 반 ID. null이면 전체공지 */
    private Long classId;

    /** 제목 (필수) */
    @Column(nullable = false, length = 200)
    private String title;

    /** 내용 (필수) */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 작성자 회원 ID (선택, rea_members.id) */
    private Long authorId;

    /** 작성자명 스냅샷 (선택) */
    @Column(length = 50)
    private String authorName;

    /** 첨부파일명 (메타데이터만, 선택) */
    @Column(length = 255)
    private String attachmentFileName;

    /** 첨부파일 키 (메타데이터만, 선택) */
    @Column(length = 255)
    private String attachmentFileKey;

    /** 조회수 (관리자 전용 표시) */
    @Column(nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    /** 활성 여부 (삭제 시 false) */
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

    /** 전체공지 여부 (classId == null) */
    public boolean isAllNotice() {
        return this.classId == null;
    }

    /** 조회수 증가 */
    public void increaseViewCount() {
        this.viewCount = (this.viewCount == null ? 0 : this.viewCount) + 1;
    }

    /** 공지 수정. null인 항목은 변경하지 않는다. classId는 changeToAll로 전체공지 전환. */
    public void update(String title, String content, Long classId, boolean changeToAll,
                       String attachmentFileName, String attachmentFileKey) {
        if (title != null) this.title = title;
        if (content != null) this.content = content;
        if (changeToAll) {
            this.classId = null;
        } else if (classId != null) {
            this.classId = classId;
        }
        if (attachmentFileName != null) {
            this.attachmentFileName = attachmentFileName.isBlank() ? null : attachmentFileName;
        }
        if (attachmentFileKey != null) {
            this.attachmentFileKey = attachmentFileKey.isBlank() ? null : attachmentFileKey;
        }
    }

    /** 공지 삭제 (비활성) */
    public void deactivate() {
        this.active = false;
    }
}
