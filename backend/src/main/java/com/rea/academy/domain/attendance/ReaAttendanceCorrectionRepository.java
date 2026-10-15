package com.rea.academy.domain.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaAttendanceCorrectionRepository extends JpaRepository<ReaAttendanceCorrection, Long> {

    /** 특정 출결 이벤트의 정정 이력 (최신 순) */
    List<ReaAttendanceCorrection> findByAcademyIdAndEventIdOrderByCorrectedAtDesc(
            Long academyId, Long eventId);
}
