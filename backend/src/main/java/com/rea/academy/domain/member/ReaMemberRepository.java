package com.rea.academy.domain.member;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReaMemberRepository extends JpaRepository<ReaMember, Long> {
    Optional<ReaMember> findByAuthKeyAndActiveTrue(String authKey);
    List<ReaMember> findByAcademyIdAndActiveTrue(Long academyId);
    List<ReaMember> findByAcademyIdAndRoleAndActiveTrue(Long academyId, ReaRole role);
    boolean existsByAuthKey(String authKey);
}
