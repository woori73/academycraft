package com.rea.academy.domain.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReaAcademyRepository extends JpaRepository<ReaAcademy, Long> {

    /** 특정 학원의 지점 목록 (ID 순) */
    List<ReaAcademy> findByParentAcademyIdAndActiveTrueOrderByIdAsc(Long parentAcademyId);

    /** 지점 등록 시 새 academyId 발급용 (등록된 학원 중 최대값) */
    @Query("SELECT COALESCE(MAX(a.id), 0) FROM ReaAcademy a")
    Long findMaxId();
}
