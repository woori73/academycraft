package com.rea.academy.domain.learning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaGradeRepository extends JpaRepository<ReaGrade, Long> {

    /** 학생별 성적 이력 — 시험일자 최신순 */
    List<ReaGrade> findByAcademyIdAndStudentIdOrderByExamDateDescIdDesc(
            Long academyId, Long studentId);
}
