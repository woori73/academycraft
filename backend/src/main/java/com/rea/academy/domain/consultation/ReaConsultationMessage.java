package com.rea.academy.domain.consultation;

import com.rea.academy.common.ReaTime;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 상담 메시지 엔티티 (스레드 내 1:1 메시지).
 * - 학부모 발신: senderStudentId (학생 인증키 로그인 사용자)
 * - 학원 발신: senderMemberId (원장·강사 회원)
 * 발신자명은 작성 당시 스냅샷으로 보존한다.
 * 첨부파일은 메타데이터(파일명·키)만 저장한다. 실제 업로드는 FileStorage 연동 시 확장.
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_consultation_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaConsultationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 소속 스레드 ID */
    @Column(nullable = false)
    private Long consultationId;

    /** 학부모(학생) 발신자 ID — 학부모 발신인 경우만 설정 */
    private Long senderStudentId;

    /** 학원(원장·강사) 발신자 ID — 학원 발신인 경우만 설정 */
    private Long senderMemberId;

    /** 발신 당시 발신자명 스냅샷 */
    @Column(length = 50)
    private String senderName;

    /** 메시지 본문 */
    @Column(nullable = false, length = 2000)
    private String content;

    /** 첨부파일명 (메타데이터, 선택) */
    private String attachmentFileName;

    /** 첨부파일 키 (메타데이터, 선택) */
    private String attachmentFileKey;

    /** 상대방 읽음 시각 (읽음 처리 시) */
    private LocalDateTime readAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = ReaTime.now();
    }

    /** 읽음 처리 */
    public void markRead() {
        this.readAt = ReaTime.now();
    }

    public boolean isRead() {
        return this.readAt != null;
    }

    /** 학부모 발신 여부 */
    public boolean isFromParent() {
        return this.senderStudentId != null;
    }
}
