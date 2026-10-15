package com.rea.academy.domain.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReaAttendanceSettingRepository extends JpaRepository<ReaAttendanceSetting, Long> {

    /** 학원의 출결 설정 (학원별 1행) */
    Optional<ReaAttendanceSetting> findByAcademyId(Long academyId);
}
