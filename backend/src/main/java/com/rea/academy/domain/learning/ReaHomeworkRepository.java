package com.rea.academy.domain.learning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaHomeworkRepository extends JpaRepository<ReaHomework, Long> {

    /** 학원 전체 숙제 (마감일 순) */
    List<ReaHomework> findByAcademyIdOrderByDueDateAscIdAsc(Long academyId);

    /** 반별 숙제 (마감일 순) */
    List<ReaHomework> findByAcademyIdAndClassIdOrderByDueDateAscIdAsc(
            Long academyId, Long classId);

    /** 학생별 개별 숙제 (마감일 순) */
    List<ReaHomework> findByAcademyIdAndStudentIdOrderByDueDateAscIdAsc(
            Long academyId, Long studentId);

    /** 여러 반에 걸친 반별 숙제 (마감일 순) */
    List<ReaHomework> findByAcademyIdAndClassIdInOrderByDueDateAscIdAsc(
            Long academyId, List<Long> classIds);
}
