package com.rea.academy.controller;

import com.rea.academy.dto.OpsDto;
import com.rea.academy.service.OpsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 운영 관리 API (7단계).
 * - 일정: 등록·월별 조회·수정·삭제 (상담 스레드 연결 지원)
 * - 강사 담당: 담당 반 + 담당 업무 메모 조회·수정
 * - 통합 검색: 학생 이름으로 출결·성적·숙제·상담·공지 횡단 검색
 * - 통계 대시보드
 * - CSV: 학생 내보내기·가져오기, 출결 내보내기 (엑셀 호환)
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 */
@RestController
@RequiredArgsConstructor
public class OpsController {

    private static final String BASE = "/api/rea/{academyId}";

    private final OpsService opsService;

    // ===== 일정·캘린더 =====

    /**
     * 일정 등록.
     * body: {title, date(yyyy-MM-dd), startTime?(HH:mm), endTime?(HH:mm),
     *        targetType?(ALL|CLASS), classId?(CLASS면 필수), consultationId?(상담 일정 연결), memo?}
     */
    @PostMapping(BASE + "/schedules")
    public ResponseEntity<OpsDto.ScheduleResponse> createSchedule(
            @PathVariable Long academyId,
            @RequestBody OpsDto.ScheduleCreateRequest req) {
        return ResponseEntity.ok(opsService.createSchedule(academyId, req));
    }

    /**
     * 월별 일정 조회 (캘린더용).
     * ?yearMonth= (필수, yyyy-MM)
     */
    @GetMapping(BASE + "/schedules")
    public ResponseEntity<List<OpsDto.ScheduleResponse>> listSchedules(
            @PathVariable Long academyId,
            @RequestParam String yearMonth) {
        return ResponseEntity.ok(opsService.listSchedules(academyId, yearMonth));
    }

    /**
     * 일정 수정 (PUT). body의 null 항목은 변경하지 않는다.
     */
    @PutMapping(BASE + "/schedules/{id}")
    public ResponseEntity<OpsDto.ScheduleResponse> updateSchedule(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestBody OpsDto.ScheduleUpdateRequest req) {
        return ResponseEntity.ok(opsService.updateSchedule(academyId, id, req));
    }

    /**
     * 일정 삭제.
     */
    @DeleteMapping(BASE + "/schedules/{id}")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable Long academyId,
            @PathVariable Long id) {
        opsService.deleteSchedule(academyId, id);
        return ResponseEntity.noContent().build();
    }

    // ===== 강사 담당 =====

    /**
     * 강사 담당 현황: 담당 반 목록(학생 수 포함) + 담당 업무 메모.
     */
    @GetMapping(BASE + "/teachers/{teacherId}/assignments")
    public ResponseEntity<OpsDto.TeacherAssignmentResponse> getTeacherAssignments(
            @PathVariable Long academyId,
            @PathVariable Long teacherId) {
        return ResponseEntity.ok(opsService.getTeacherAssignments(academyId, teacherId));
    }

    /**
     * 강사 담당 업무 메모 수정 (upsert).
     * body: {dutyMemo}
     */
    @PutMapping(BASE + "/teachers/{teacherId}/assignments/memo")
    public ResponseEntity<OpsDto.TeacherAssignmentResponse> updateDutyMemo(
            @PathVariable Long academyId,
            @PathVariable Long teacherId,
            @RequestBody OpsDto.DutyMemoUpdateRequest req) {
        return ResponseEntity.ok(opsService.updateDutyMemo(
                academyId, teacherId, req.getDutyMemo()));
    }

    // ===== 통합 검색 =====

    /**
     * 통합 검색: 학생 이름으로 학생·출결·성적·숙제·상담·공지를 횡단 검색.
     * ?query= (필수)
     */
    @GetMapping(BASE + "/search")
    public ResponseEntity<OpsDto.SearchResponse> search(
            @PathVariable Long academyId,
            @RequestParam String query) {
        return ResponseEntity.ok(opsService.search(academyId, query));
    }

    // ===== 통계 대시보드 =====

    /**
     * 통계 대시보드 (OPS-02):
     * 활성 학생 수·활성 반 수·오늘 출결 현황·미답변 상담 수·이번 달 공지 수.
     */
    @GetMapping(BASE + "/dashboard/stats")
    public ResponseEntity<OpsDto.DashboardStatsResponse> getDashboardStats(
            @PathVariable Long academyId) {
        return ResponseEntity.ok(opsService.getDashboardStats(academyId));
    }

    // ===== CSV 가져오기·내보내기 (엑셀 호환) =====

    /**
     * 학생 목록 CSV 내보내기 (엑셀 호환: UTF-8 BOM).
     * 열: 이름,학년,학교,인증키,학부모명,학부모연락처,반,등록일
     */
    @GetMapping(BASE + "/export/students.csv")
    public ResponseEntity<byte[]> exportStudentsCsv(@PathVariable Long academyId) {
        byte[] csv = opsService.exportStudentsCsv(academyId);
        return csvDownload(csv, "rea-students-" + academyId + ".csv");
    }

    /**
     * 출결 기록 CSV 내보내기 (반별·월별, 엑셀 호환: UTF-8 BOM).
     * ?classId= (선택, 없으면 학원 전체) ?yearMonth= (필수, yyyy-MM)
     * 열: 일자,이름,반,구분,발생시각,메모
     */
    @GetMapping(BASE + "/export/attendance.csv")
    public ResponseEntity<byte[]> exportAttendanceCsv(
            @PathVariable Long academyId,
            @RequestParam(required = false) Long classId,
            @RequestParam String yearMonth) {
        byte[] csv = opsService.exportAttendanceCsv(academyId, classId, yearMonth);
        return csvDownload(csv, "rea-attendance-" + academyId + "-" + yearMonth + ".csv");
    }

    /**
     * 학생 일괄 등록 CSV 가져오기.
     * Content-Type: text/csv. 열 순서: 이름,학년,반,학부모명,연락처 (헤더 행 선택)
     */
    @PostMapping(BASE + "/import/students.csv")
    public ResponseEntity<OpsDto.StudentImportResponse> importStudentsCsv(
            @PathVariable Long academyId,
            @RequestBody String csv) {
        return ResponseEntity.ok(opsService.importStudentsCsv(academyId, csv));
    }

    // ===== 내부 =====

    private ResponseEntity<byte[]> csvDownload(byte[] csv, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentLength(csv.length);
        return ResponseEntity.ok().headers(headers).body(csv);
    }
}
