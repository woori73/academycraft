package com.rea.academy.domain.clazz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassEnrollmentRepository extends JpaRepository<ClassEnrollment, Long> {
    List<ClassEnrollment> findByClassIdAndActiveTrue(Long classId);
    Optional<ClassEnrollment> findByClassIdAndStudentIdAndActiveTrue(Long classId, Long studentId);
    List<ClassEnrollment> findByStudentIdAndActiveTrue(Long studentId);
    long countByClassIdAndActiveTrue(Long classId);
}
