package com.rea.academy.domain.consultation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaConsultationRepository extends JpaRepository<ReaConsultation, Long> {

    /** 학원 전체 상담 스레드 — 최신순 (관리자용) */
    List<ReaConsultation> findByAcademyIdAndActiveTrueOrderByIdDesc(Long academyId);

    /** 특정 학생(학부모)의 상담 스레드 — 최신순 */
    List<ReaConsultation> findByAcademyIdAndStudentIdAndActiveTrueOrderByIdDesc(
            Long academyId, Long studentId);
}
