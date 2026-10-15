package com.rea.academy.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReaNoticeRepository extends JpaRepository<ReaNotice, Long> {

    List<ReaNotice> findByAcademyIdAndActiveTrueOrderByIdDesc(Long academyId);

    List<ReaNotice> findByAcademyIdAndClassIdAndActiveTrueOrderByIdDesc(Long academyId, Long classId);

    List<ReaNotice> findByAcademyIdAndClassIdIsNullAndActiveTrueOrderByIdDesc(Long academyId);

    /**
     * 학부모용 조회 범위: 전체공지 + 소속 반 공지 (최신순).
     * classIds가 비어 있으면 전체공지(ALL)만 해당하므로 호출 측에서 빈 리스트 가드를 한다.
     */
    @Query("SELECT n FROM ReaNotice n WHERE n.academyId = :academyId AND n.active = true " +
            "AND (n.classId IS NULL OR n.classId IN :classIds) ORDER BY n.id DESC")
    List<ReaNotice> findVisibleNotices(
            @Param("academyId") Long academyId, @Param("classIds") List<Long> classIds);
}
