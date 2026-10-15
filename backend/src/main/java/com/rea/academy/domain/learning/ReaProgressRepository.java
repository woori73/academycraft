package com.rea.academy.domain.learning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaProgressRepository extends JpaRepository<ReaProgress, Long> {

    /** 학생별 진도 기록 — 기록일자 최신순 */
    List<ReaProgress> findByAcademyIdAndStudentIdOrderByRecordedDateDescIdDesc(
            Long academyId, Long studentId);
}
