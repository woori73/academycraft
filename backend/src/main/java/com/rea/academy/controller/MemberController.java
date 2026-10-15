package com.rea.academy.controller;

import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 회원·학생 관리 API.
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 */
@RestController
@RequestMapping("/api/rea/{academyId}")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    // ===== 원장·강사 =====

    @PostMapping("/members")
    public ResponseEntity<MemberDto.Response> createMember(
            @PathVariable Long academyId,
            @RequestBody MemberDto.CreateRequest req) {
        req.setAcademyId(academyId);
        return ResponseEntity.ok(memberService.createMember(req));
    }

    @GetMapping("/members")
    public ResponseEntity<List<MemberDto.Response>> getMembers(
            @PathVariable Long academyId,
            @RequestParam(required = false) ReaRole role) {
        return ResponseEntity.ok(memberService.getMembers(academyId, role));
    }

    @GetMapping("/members/{memberId}")
    public ResponseEntity<MemberDto.Response> getMemberDetail(
            @PathVariable Long academyId,
            @PathVariable Long memberId) {
        return ResponseEntity.ok(memberService.getMemberDetail(academyId, memberId));
    }

    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<Void> deactivateMember(
            @PathVariable Long academyId,
            @PathVariable Long memberId) {
        memberService.deactivateMember(academyId, memberId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/members/{memberId}/renew-key")
    public ResponseEntity<MemberDto.Response> renewMemberKey(
            @PathVariable Long academyId,
            @PathVariable Long memberId) {
        return ResponseEntity.ok(memberService.renewMemberKey(academyId, memberId));
    }

    // ===== 학생 =====

    @PostMapping("/students")
    public ResponseEntity<StudentDto.DetailResponse> createStudent(
            @PathVariable Long academyId,
            @RequestBody StudentDto.CreateRequest req) {
        req.setAcademyId(academyId);
        return ResponseEntity.ok(memberService.createStudent(req));
    }

    @GetMapping("/students")
    public ResponseEntity<List<StudentDto.Response>> getStudents(
            @PathVariable Long academyId,
            @RequestParam(required = false) String name) {
        return ResponseEntity.ok(memberService.getStudents(academyId, name));
    }

    /**
     * 비활성 학생 목록 (원장 전용). 기록 보존 목적으로 조회 가능하다.
     */
    @GetMapping("/students/inactive")
    public ResponseEntity<List<StudentDto.Response>> getInactiveStudents(
            @PathVariable Long academyId) {
        return ResponseEntity.ok(memberService.getInactiveStudents(academyId));
    }

    @GetMapping("/students/{studentId}")
    public ResponseEntity<StudentDto.DetailResponse> getStudentDetail(
            @PathVariable Long academyId,
            @PathVariable Long studentId) {
        return ResponseEntity.ok(memberService.getStudentDetail(academyId, studentId));
    }

    @PutMapping("/students/{studentId}")
    public ResponseEntity<StudentDto.DetailResponse> updateStudent(
            @PathVariable Long academyId,
            @PathVariable Long studentId,
            @RequestBody StudentDto.UpdateRequest req) {
        return ResponseEntity.ok(memberService.updateStudent(academyId, studentId, req));
    }

    @DeleteMapping("/students/{studentId}")
    public ResponseEntity<Void> deactivateStudent(
            @PathVariable Long academyId,
            @PathVariable Long studentId) {
        memberService.deactivateStudent(academyId, studentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/students/{studentId}/renew-key")
    public ResponseEntity<StudentDto.DetailResponse> renewStudentKey(
            @PathVariable Long academyId,
            @PathVariable Long studentId) {
        return ResponseEntity.ok(memberService.renewStudentKey(academyId, studentId));
    }
}
