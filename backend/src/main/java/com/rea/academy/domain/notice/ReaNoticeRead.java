package com.rea.academy.domain.notice;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 공지 읽음 확인 기록.
 * 누가(학생=학부모) 어떤 공지를 읽었는지 1건당 1행.
 * (academyId, noticeId, studentId)는 유일하다.
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_notice_reads",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_read",
                columnNames = {"academyId", "noticeId", "studentId"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaNoticeRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 공지 ID */
    @Column(nullable = false)
    private Long noticeId;

    /** 읽은 학생 ID (학부모 = 학생 인증키 로그인 사용자) */
    @Column(nullable = false)
    private Long studentId;

    /** 읽음 시각 */
    @Column(nullable = false)
    private LocalDateTime readAt;

    @PrePersist
    protected void onCreate() {
        if (this.readAt == null) {
            this.readAt = LocalDateTime.now();
        }
    }
}
