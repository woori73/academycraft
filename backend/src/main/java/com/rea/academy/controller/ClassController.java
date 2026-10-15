package com.rea.academy.controller;

import com.rea.academy.dto.ClassDto;
import com.rea.academy.service.ClassService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 반 관리·시간표 API.
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 */
@RestController
@RequestMapping("/api/rea/{academyId}")
@RequiredArgsConstructor
public class ClassController {

    private final ClassService classService;

    // ===== 반 CRUD =====

    @PostMapping("/classes")
    public ResponseEntity<ClassDto.DetailResponse> createClass(
            @PathVariable Long academyId,
            @RequestBody ClassDto.CreateRequest req) {
        req.setAcademyId(academyId);
        return ResponseEntity.ok(classService.createClass(academyId, req));
    }

    @GetMapping("/classes")
    public ResponseEntity<List<ClassDto.Response>> getClasses(
            @PathVariable Long academyId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(classService.getClasses(academyId, includeInactive));
    }

    @GetMapping("/classes/{classId}")
    public ResponseEntity<ClassDto.DetailResponse> getClassDetail(
            @PathVariable Long academyId,
            @PathVariable Long classId) {
        return ResponseEntity.ok(classService.getClassDetail(academyId, classId));
    }

    @PutMapping("/classes/{classId}")
    public ResponseEntity<ClassDto.DetailResponse> updateClass(
            @PathVariable Long academyId,
            @PathVariable Long classId,
            @RequestBody ClassDto.UpdateRequest req) {
        return ResponseEntity.ok(classService.updateClass(academyId, classId, req));
    }

    @PostMapping("/classes/{classId}/close")
    public ResponseEntity<Void> closeClass(
            @PathVariable Long academyId,
            @PathVariable Long classId) {
        classService.closeClass(academyId, classId);
        return ResponseEntity.noContent().build();
    }

    // ===== 학생 배정·제외·이동 =====

    @PostMapping("/classes/{classId}/students")
    public ResponseEntity<ClassDto.DetailResponse> assignStudents(
            @PathVariable Long academyId,
            @PathVariable Long classId,
            @RequestBody ClassDto.AssignRequest req) {
        return ResponseEntity.ok(
                classService.assignStudents(academyId, classId, req.getStudentIds()));
    }

    @DeleteMapping("/classes/{classId}/students/{studentId}")
    public ResponseEntity<Void> removeStudent(
            @PathVariable Long academyId,
            @PathVariable Long classId,
            @PathVariable Long studentId) {
        classService.removeStudent(academyId, classId, studentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/classes/{classId}/students/{studentId}/move")
    public ResponseEntity<ClassDto.DetailResponse> moveStudent(
            @PathVariable Long academyId,
            @PathVariable Long classId,
            @PathVariable Long studentId,
            @RequestBody ClassDto.MoveRequest req) {
        return ResponseEntity.ok(
                classService.moveStudent(academyId, classId, studentId, req.getToClassId()));
    }

    // ===== 주간 시간표 =====

    @GetMapping("/timetable/weekly")
    public ResponseEntity<List<ClassDto.TimetableEntry>> getWeeklyTimetable(
            @PathVariable Long academyId) {
        return ResponseEntity.ok(classService.getWeeklyTimetable(academyId));
    }
}
