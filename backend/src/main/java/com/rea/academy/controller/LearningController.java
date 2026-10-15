package com.rea.academy.controller;

import com.rea.academy.dto.LearningDto;
import com.rea.academy.service.LearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 학습 관리 API (6단계).
 * - 숙제: 등록(반별/학생별)·목록·검사 + 등록 시 HOMEWORK 알림
 * - 진도: 기록·학생별 조회
 * - 성적: 등록·학생별 이력 + 등록 시 GRADE 알림
 * - 리포트: 학생별 성적·숙제·진도·출결 종합 데이터
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 */
@RestController
@RequiredArgsConstructor
public class LearningController {

    private static final String BASE = "/api/rea/{academyId}";

    private final LearningService learningService;

    // ===== 숙제 =====

    /**
     * 숙제 등록 (강사).
     * body: {classId?(반별), studentId?(학생별), title, content, dueDate(yyyy-MM-dd),
     *        questionRefs?(내신Wiki 문항 참조)}
     * 등록 시 대상 학부모에게 HOMEWORK 알림 발송.
     */
    @PostMapping(BASE + "/homework")
    public ResponseEntity<LearningDto.HomeworkCreateResponse> createHomework(
            @PathVariable Long academyId,
            @RequestBody LearningDto.HomeworkCreateRequest req) {
        return ResponseEntity.ok(learningService.createHomework(academyId, req));
    }

    /**
     * 숙제 목록.
     * ?classId= (반별 숙제) / ?studentId= 또는 ?authKey= (학부모: 자녀 숙제+검사 상태)
     * 둘 다 없으면 학원 전체 숙제.
     */
    @GetMapping(BASE + "/homework")
    public ResponseEntity<List<LearningDto.HomeworkResponse>> listHomework(
            @PathVariable Long academyId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(
                learningService.listHomework(academyId, classId, studentId, authKey));
    }

    /**
     * 숙제 검사 (강사).
     * body: {studentId, status(COMPLETED|INCOMPLETE), score?, comment?}
     */
    @PostMapping(BASE + "/homework/{id}/check")
    public ResponseEntity<LearningDto.HomeworkCheckResponse> checkHomework(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestBody LearningDto.HomeworkCheckRequest req) {
        return ResponseEntity.ok(learningService.checkHomework(academyId, id, req));
    }

    /**
     * 숙제별 검사 결과 목록 (강사, HW-02).
     */
    @GetMapping(BASE + "/homework/{id}/checks")
    public ResponseEntity<List<LearningDto.HomeworkCheckResponse>> listHomeworkChecks(
            @PathVariable Long academyId,
            @PathVariable Long id) {
        return ResponseEntity.ok(learningService.listHomeworkChecks(academyId, id));
    }

    // ===== 진도 =====

    /**
     * 학습 진도 기록 (강사).
     * body: {studentId, unit(단원), progressRate(0~100), memo?, recordedDate?(yyyy-MM-dd)}
     */
    @PostMapping(BASE + "/progress")
    public ResponseEntity<LearningDto.ProgressResponse> recordProgress(
            @PathVariable Long academyId,
            @RequestBody LearningDto.ProgressRecordRequest req) {
        return ResponseEntity.ok(learningService.recordProgress(academyId, req));
    }

    /**
     * 학생별 진도 조회 — 기록일자 최신순.
     * ?authKey= (학부모: 본인 인증키로 조회 가능)
     */
    @GetMapping(BASE + "/progress/student/{studentId}")
    public ResponseEntity<List<LearningDto.ProgressResponse>> getProgress(
            @PathVariable Long academyId,
            @PathVariable Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(learningService.getProgress(academyId, studentId, authKey));
    }

    // ===== 성적 =====

    /**
     * 성적 등록 (강사).
     * body: {studentId, examName, subject, score, fullScore, examDate?(yyyy-MM-dd), note?}
     * 등록 시 해당 학부모에게 GRADE 알림 발송.
     */
    @PostMapping(BASE + "/grades")
    public ResponseEntity<LearningDto.GradeCreateResponse> createGrade(
            @PathVariable Long academyId,
            @RequestBody LearningDto.GradeCreateRequest req) {
        return ResponseEntity.ok(learningService.createGrade(academyId, req));
    }

    /**
     * 학생별 성적 이력 — 시험일자 최신순.
     * ?authKey= (학부모: 본인 인증키로 조회 가능)
     */
    @GetMapping(BASE + "/grades/student/{studentId}")
    public ResponseEntity<List<LearningDto.GradeResponse>> getGrades(
            @PathVariable Long academyId,
            @PathVariable Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(learningService.getGrades(academyId, studentId, authKey));
    }

    // ===== 학습 리포트 =====

    /**
     * 학습 리포트 데이터 (RPT-01).
     * 성적·숙제·진도·출결 종합 집계.
     * ?authKey= (학부모: 본인 인증키로 조회 가능)
     */
    @GetMapping(BASE + "/reports/student/{studentId}")
    public ResponseEntity<LearningDto.ReportResponse> getReport(
            @PathVariable Long academyId,
            @PathVariable Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(learningService.getReport(academyId, studentId, authKey));
    }
}
