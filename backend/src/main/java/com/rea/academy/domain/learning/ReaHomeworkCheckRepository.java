package com.rea.academy.domain.learning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReaHomeworkCheckRepository extends JpaRepository<ReaHomeworkCheck, Long> {

    /** (숙제, 학생) 검사 결과 1건 */
    Optional<ReaHomeworkCheck> findByHomeworkIdAndStudentId(Long homeworkId, Long studentId);

    /** 숙제별 전체 검사 결과 */
    List<ReaHomeworkCheck> findByAcademyIdAndHomeworkId(Long academyId, Long homeworkId);

    /** 학생별 전체 검사 결과 */
    List<ReaHomeworkCheck> findByAcademyIdAndStudentId(Long academyId, Long studentId);
}
