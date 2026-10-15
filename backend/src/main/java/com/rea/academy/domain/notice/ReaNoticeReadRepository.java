package com.rea.academy.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReaNoticeReadRepository extends JpaRepository<ReaNoticeRead, Long> {

    List<ReaNoticeRead> findByAcademyIdAndNoticeIdOrderByReadAtAsc(Long academyId, Long noticeId);

    Optional<ReaNoticeRead> findByAcademyIdAndNoticeIdAndStudentId(
            Long academyId, Long noticeId, Long studentId);

    long countByAcademyIdAndNoticeId(Long academyId, Long noticeId);
}
