package com.rea.academy.domain.ops;

import com.rea.academy.domain.attendance.ReaAttendanceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 7단계 운영 관리용 출결 조회 Repository.
 *
 * 기존 {@code ReaAttendanceEventRepository}를 상속하지 않는다:
 * 상속하면 같은 타입의 빈이 2개 생성되어 기존 AttendanceService의
 * 생성자 주입이 깨지므로(NoUniqueBeanDefinitionException) 독립 인터페이스로 둔다.
 * 기존 코드는 수정하지 않는다.
 */
public interface OpsAttendanceEventRepository extends JpaRepository<ReaAttendanceEvent, Long> {

    /** 학원 전체 기간 내 출결 이벤트 (대시보드 오늘 출결·CSV 월별 내보내기용) */
    List<ReaAttendanceEvent> findByAcademyIdAndOccurredAtBetween(
            Long academyId, LocalDateTime from, LocalDateTime to);

    /** 학생의 전체 출결 이벤트 (최신 순, 통합 검색용) */
    List<ReaAttendanceEvent> findByAcademyIdAndStudentIdOrderByOccurredAtDesc(
            Long academyId, Long studentId);
}
