package com.rea.academy.domain.member;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReaStudentRepository extends JpaRepository<ReaStudent, Long> {
    Optional<ReaStudent> findByAuthKeyAndActiveTrue(String authKey);
    List<ReaStudent> findByAcademyIdAndActiveTrue(Long academyId);
    List<ReaStudent> findByAcademyIdAndNameContainingAndActiveTrue(Long academyId, String name);
    /**
     * 비활성 학생 조회 (원장 전용, 기록 보존 목적).
     * 비활성화된 학생의 데이터는 삭제되지 않고 보존되며,
     * 원장이 명시적으로 조회할 수 있다.
     */
    List<ReaStudent> findByAcademyIdAndActiveFalse(Long academyId);
    boolean existsByAuthKey(String authKey);
}
