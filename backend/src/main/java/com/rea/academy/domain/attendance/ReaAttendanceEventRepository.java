package com.rea.academy.domain.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ReaAttendanceEventRepository extends JpaRepository<ReaAttendanceEvent, Long> {

    /** 학생의 기간 내 출결 이벤트 (발생 일시 순) */
    List<ReaAttendanceEvent> findByAcademyIdAndStudentIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            Long academyId, Long studentId, LocalDateTime from, LocalDateTime to);

    /** 학생의 전체 출결 이벤트 (최신 순) */
    List<ReaAttendanceEvent> findByAcademyIdAndStudentIdOrderByOccurredAtDesc(
            Long academyId, Long studentId);
}
