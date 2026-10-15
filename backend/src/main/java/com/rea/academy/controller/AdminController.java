package com.rea.academy.controller;

import com.rea.academy.dto.AdminDto;
import com.rea.academy.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 학원 관리·관리자 API (8단계).
 * - 학원 기본정보 조회·수정 (이름·전화·주소·로고 키)
 * - 지점 관리: 본원·지점 관계
 * - 사용자·권한 관리: 역할별 사용자 목록, 권한 변경(원장만), 학부모 연결 확인
 * - 변경 이력(감사 로그) 조회
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 */
@RestController
@RequiredArgsConstructor
public class AdminController {

    private static final String BASE = "/api/rea/{academyId}";

    private final AdminService adminService;

    // ===== 학원 기본정보 =====

    /**
     * 학원 정보 조회.
     * 미등록 학원이면 400 (PUT으로 먼저 등록).
     */
    @GetMapping(BASE + "/info")
    public ResponseEntity<AdminDto.AcademyInfoResponse> getAcademyInfo(
            @PathVariable Long academyId) {
        return ResponseEntity.ok(adminService.getAcademyInfo(academyId));
    }

    /**
     * 학원 정보 등록·수정 (upsert).
     * - 미등록: 신규 등록 (name 필수) — "학원 등록"
     * - 등록済: 제공된 항목만 수정 (null은 변경 없음)
     * body: {name?, phone?, address?, logoKey?, memo?, actorMemberId?}
     */
    @PutMapping(BASE + "/info")
    public ResponseEntity<AdminDto.AcademyInfoResponse> saveAcademyInfo(
            @PathVariable Long academyId,
            @RequestBody AdminDto.AcademyInfoUpdateRequest req) {
        return ResponseEntity.ok(adminService.saveAcademyInfo(academyId, req));
    }

    // ===== 지점 관리 =====

    /**
     * 지점 목록 조회 (본원 academyId 기준).
     */
    @GetMapping(BASE + "/branches")
    public ResponseEntity<List<AdminDto.AcademyInfoResponse>> listBranches(
            @PathVariable Long academyId) {
        return ResponseEntity.ok(adminService.listBranches(academyId));
    }

    /**
     * 지점 등록.
     * - 본원은 PUT /info로 먼저 등록되어 있어야 함
     * - 새 academyId는 서버가 자동 발급
     * body: {name(필수), phone?, address?, logoKey?, memo?, actorMemberId?}
     */
    @PostMapping(BASE + "/branches")
    public ResponseEntity<AdminDto.AcademyInfoResponse> createBranch(
            @PathVariable Long academyId,
            @RequestBody AdminDto.BranchCreateRequest req) {
        return ResponseEntity.ok(adminService.createBranch(academyId, req));
    }

    // ===== 사용자·권한 관리 =====

    /**
     * 역할별 사용자 목록.
     * ?role=OWNER(원장) | TEACHER(강사) | PARENT(학부모 연결) — 미지정 시 원장·강사 전체
     */
    @GetMapping(BASE + "/users")
    public ResponseEntity<AdminDto.UserListResponse> listUsers(
            @PathVariable Long academyId,
            @RequestParam(required = false) String role) {
        return ResponseEntity.ok(adminService.listUsers(academyId, role));
    }

    /**
     * 권한 변경 (원장만 수행 가능).
     * body: {role(필수, OWNER|ACADEMY_OWNER|TEACHER), actorMemberId(필수, 요청자=원장)}
     * - 강사 → 원장 승격 허용. 원장 교체(인수인계)는 "후임 승격 → 전임 강등" 2단계로 수행
     * - 마지막 원장의 강등 방지: 409
     */
    @PutMapping(BASE + "/users/{memberId}/role")
    public ResponseEntity<AdminDto.StaffResponse> changeMemberRole(
            @PathVariable Long academyId,
            @PathVariable Long memberId,
            @RequestBody AdminDto.RoleChangeRequest req) {
        return ResponseEntity.ok(adminService.changeMemberRole(academyId, memberId, req));
    }

    // ===== 변경 이력 (감사 로그) =====

    /**
     * 변경 이력 조회 (관리자용, 최신순).
     * ?actionType= (선택, 예: STUDENT_CREATED — 대소문자 무시)
     */
    @GetMapping(BASE + "/audit-logs")
    public ResponseEntity<AdminDto.AuditLogListResponse> listAuditLogs(
            @PathVariable Long academyId,
            @RequestParam(required = false) String actionType) {
        return ResponseEntity.ok(adminService.listAuditLogs(academyId, actionType));
    }
}
