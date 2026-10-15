package com.rea.academy.domain.ops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReaTeacherAssignmentRepository extends JpaRepository<ReaTeacherAssignment, Long> {

    /** 강사별 담당 업무 메모 1건 */
    Optional<ReaTeacherAssignment> findByAcademyIdAndTeacherId(Long academyId, Long teacherId);
}
