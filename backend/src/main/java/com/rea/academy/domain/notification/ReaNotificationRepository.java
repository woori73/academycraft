package com.rea.academy.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaNotificationRepository extends JpaRepository<ReaNotification, Long> {

    /** 수신자(학생)별 알림 목록 — 최신순 */
    List<ReaNotification> findByAcademyIdAndRecipientStudentIdOrderByCreatedAtDesc(
            Long academyId, Long recipientStudentId);

    /** 학원 전체 발송 이력 — 최신순 (관리자) */
    List<ReaNotification> findByAcademyIdOrderByCreatedAtDesc(Long academyId);

    /** 미읽음 건수 */
    long countByAcademyIdAndRecipientStudentIdAndReadAtIsNull(
            Long academyId, Long recipientStudentId);
}
