package com.rea.academy.domain.member;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReaMemberRepository extends JpaRepository<ReaMember, Long> {
    Optional<ReaMember> findByAuthKeyAndActiveTrue(String authKey);
    Optional<ReaMember> findByLoginIdAndActiveTrue(String loginId);
    Optional<ReaMember> findByLoginId(String loginId);
    List<ReaMember> findByAcademyIdAndActiveTrue(Long academyId);
    List<ReaMember> findByAcademyIdAndRoleAndActiveTrue(Long academyId, ReaRole role);
    boolean existsByAuthKey(String authKey);
    boolean existsByLoginId(String loginId);
    Optional<ReaMember> findByPasswordResetToken(String passwordResetToken);
}
