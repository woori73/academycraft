package com.rea.academy.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReaNotificationSettingRepository extends JpaRepository<ReaNotificationSetting, Long> {

    Optional<ReaNotificationSetting> findByAcademyIdAndStudentIdAndType(
            Long academyId, Long studentId, NotificationType type);

    List<ReaNotificationSetting> findByAcademyIdAndStudentId(Long academyId, Long studentId);
}
