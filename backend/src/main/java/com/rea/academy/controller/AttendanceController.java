package com.rea.academy.controller;

import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 출결 관리 API.
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 */
@RestController
@RequestMapping("/api/rea/{academyId}/attendance")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    // ===== 출결 기록 =====

    /**
     * 출결 기록 (강사·원장 직접 기록).
     * body: {studentId 또는 authKey, eventType, occurredAt(선택), note(선택)}
     */
    @PostMapping("/check")
    public ResponseEntity<AttendanceDto.CheckResponse> check(
            @PathVariable Long academyId,
            @RequestBody AttendanceDto.CheckRequest req) {
        return ResponseEntity.ok(attendanceService.check(academyId, req));
    }

    /**
     * 키패드 출결 기록.
     * body: {authKey} — 당일 첫 기록=등원, 이후=하원으로 자동 판별.
     */
    @PostMapping("/keypad")
    public ResponseEntity<AttendanceDto.CheckResponse> keypadCheck(
            @PathVariable Long academyId,
            @RequestBody AttendanceDto.KeypadRequest req) {
        return ResponseEntity.ok(attendanceService.keypadCheck(academyId, req));
    }

    // ===== 반별 출결부 =====

    /** 반별 출결부: ?classId=&date=(yyyy-MM-dd) */
    @GetMapping("/roster")
    public ResponseEntity<AttendanceDto.RosterResponse> getRoster(
            @PathVariable Long academyId,
            @RequestParam Long classId,
            @RequestParam String date) {
        return ResponseEntity.ok(attendanceService.getRoster(academyId, classId, date));
    }

    // ===== 학생별 이력 =====

    /** 학생별 출결 이력: ?from=(yyyy-MM-dd)&to=(yyyy-MM-dd), 미지정 시 최근 30일 */
    @GetMapping("/student/{studentId}")
    public ResponseEntity<List<AttendanceDto.EventResponse>> getStudentHistory(
            @PathVariable Long academyId,
            @PathVariable Long studentId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(attendanceService.getStudentHistory(academyId, studentId, from, to));
    }

    // ===== 월별 현황 =====

    /** 월별 출결 현황: ?classId=&yearMonth=(yyyy-MM) */
    @GetMapping("/monthly")
    public ResponseEntity<AttendanceDto.MonthlyResponse> getMonthly(
            @PathVariable Long academyId,
            @RequestParam Long classId,
            @RequestParam String yearMonth) {
        return ResponseEntity.ok(attendanceService.getMonthly(academyId, classId, yearMonth));
    }

    // ===== 출결 정정 =====

    /**
     * 출결 정정.
     * body: {eventType(선택), occurredAt(선택), note(선택), reason(필수)}
     */
    @PutMapping("/{eventId}")
    public ResponseEntity<AttendanceDto.EventResponse> correctEvent(
            @PathVariable Long academyId,
            @PathVariable Long eventId,
            @RequestBody AttendanceDto.CorrectRequest req) {
        return ResponseEntity.ok(attendanceService.correctEvent(academyId, eventId, req));
    }

    // ===== 출결 설정 =====

    @GetMapping("/settings")
    public ResponseEntity<AttendanceDto.SettingsResponse> getSettings(
            @PathVariable Long academyId) {
        return ResponseEntity.ok(attendanceService.getSettings(academyId));
    }

    @PutMapping("/settings")
    public ResponseEntity<AttendanceDto.SettingsResponse> updateSettings(
            @PathVariable Long academyId,
            @RequestBody AttendanceDto.SettingsRequest req) {
        return ResponseEntity.ok(attendanceService.updateSettings(academyId, req));
    }
}
