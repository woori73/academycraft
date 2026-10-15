package com.rea.academy.domain.clazz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReaClassRepository extends JpaRepository<ReaClass, Long> {
    List<ReaClass> findByAcademyIdAndActiveTrueOrderByIdAsc(Long academyId);
    List<ReaClass> findByAcademyIdOrderByIdAsc(Long academyId);
}
