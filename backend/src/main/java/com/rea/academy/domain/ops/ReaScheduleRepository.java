package com.rea.academy.domain.ops;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ReaScheduleRepository extends JpaRepository<ReaSchedule, Long> {

    /** 월별 일정 조회 — 일자·시작시각·ID 순 */
    List<ReaSchedule> findByAcademyIdAndDateBetweenOrderByDateAscStartTimeAscIdAsc(
            Long academyId, LocalDate from, LocalDate to);

    /** 상담 스레드에 연결된 일정 목록 (상담 일정 조회용) */
    List<ReaSchedule> findByAcademyIdAndConsultationIdOrderByDateAscStartTimeAsc(
            Long academyId, Long consultationId);

    /** 일정 제목 검색 (OPS-01 검색용) */
    List<ReaSchedule> findByAcademyIdAndTitleContainingOrderByDateDesc(
            Long academyId, String title);
}
