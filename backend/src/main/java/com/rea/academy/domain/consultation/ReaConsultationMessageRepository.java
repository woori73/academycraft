package com.rea.academy.domain.consultation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReaConsultationMessageRepository extends JpaRepository<ReaConsultationMessage, Long> {

    /** 스레드 내 메시지 — 작성순 */
    List<ReaConsultationMessage> findByAcademyIdAndConsultationIdOrderByIdAsc(
            Long academyId, Long consultationId);

    /** 스레드 메시지 건수 */
    long countByAcademyIdAndConsultationId(Long academyId, Long consultationId);

    /** 스레드의 마지막 메시지 (정렬·미리보기용) */
    Optional<ReaConsultationMessage> findFirstByAcademyIdAndConsultationIdOrderByIdDesc(
            Long academyId, Long consultationId);
}
