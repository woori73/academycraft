package com.rea.academy.domain.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaAuditLogRepository extends JpaRepository<ReaAuditLog, Long> {

    /** 학원별 변경 이력 (최신순) */
    List<ReaAuditLog> findByAcademyIdOrderByIdDesc(Long academyId);

    /** 학원별 + 행위 종류별 변경 이력 (최신순) */
    List<ReaAuditLog> findByAcademyIdAndActionTypeOrderByIdDesc(
            Long academyId, AuditActionType actionType);
}
