package com.rea.academy.controller;

import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 상담 관리 API (5단계).
 * - 상담 스레드: INQUIRY(학부모 1:1 문의) / NEWS(학원→학부모 1:1 아이소식)
 * - 학원(원장·강사) 발신 메시지 → 학부모에게 CONSULT_REPLY 알림 발송
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 */
@RestController
@RequestMapping("/api/rea/{academyId}/consultations")
@RequiredArgsConstructor
public class ConsultationController {

    private final ConsultationService consultationService;

    // ===== 스레드 생성 =====

    /**
     * 상담 스레드 생성.
     * body: {studentId 또는 authKey, type(INQUIRY|NEWS), title,
     *        content(선택, INQUIRY 첫 문의/NEWS는 필수),
     *        authorMemberId(NEWS 작성자=원장·강사, 필수), attachmentFileName/attachmentFileKey(선택)}
     */
    @PostMapping
    public ResponseEntity<ConsultationDto.CreateResponse> create(
            @PathVariable Long academyId,
            @RequestBody ConsultationDto.CreateRequest req) {
        var auth=com.rea.academy.security.AuthContextHolder.getOrNull();
        if(auth!=null && !auth.isParent()) req.setAuthorMemberId(auth.principalId());
        return ResponseEntity.ok(consultationService.create(academyId, req));
    }

    // ===== 목록 =====

    /**
     * 상담 스레드 목록.
     * ?status=OPEN|ANSWERED|CLOSED (선택), ?type=INQUIRY|NEWS (선택)
     * ?studentId= 또는 ?authKey= (학부모: 본인 스레드만)
     * ?targetStudentId= (관리자: 특정 학생 스레드만)
     */
    @GetMapping
    public ResponseEntity<List<ConsultationDto.ListResponse>> list(
            @PathVariable Long academyId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String authKey,
            @RequestParam(required = false) Long targetStudentId) {
        return ResponseEntity.ok(consultationService.list(
                academyId, status, type, studentId, authKey, targetStudentId));
    }

    // ===== 상세 =====

    /**
     * 스레드 상세 + 메시지 목록.
     * ?studentId= 또는 ?authKey= (학부모: 본인 스레드만 조회 가능)
     */
    @GetMapping("/{id}")
    public ResponseEntity<ConsultationDto.DetailResponse> detail(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(
                consultationService.detail(academyId, id, studentId, authKey));
    }

    // ===== 메시지 작성 =====

    /**
     * 스레드 내 메시지 작성.
     * body: {content, attachmentFileName?, attachmentFileKey?,
     *        studentId|authKey(학부모) 또는 memberId(원장·강사)}
     * 학원 발신 시 해당 학부모에게 CONSULT_REPLY 알림이 발송된다.
     */
    @PostMapping("/{id}/messages")
    public ResponseEntity<ConsultationDto.CreateMessageResponse> addMessage(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestBody ConsultationDto.MessageRequest req) {
        var auth=com.rea.academy.security.AuthContextHolder.getOrNull();
        if(auth!=null && !auth.isParent()) {req.setMemberId(auth.principalId());req.setStudentId(null);req.setAuthKey(null);}
        return ResponseEntity.ok(consultationService.addMessage(academyId, id, req));
    }

    // ===== 스레드 종료 =====

    /**
     * 스레드 종료.
     * body: {studentId|authKey(학부모, 본인 스레드만) 또는 memberId(원장·강사)}
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<ConsultationDto.DetailResponse> close(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestBody ConsultationDto.CloseRequest req) {
        var auth=com.rea.academy.security.AuthContextHolder.getOrNull();
        if(auth!=null && !auth.isParent()) {req.setMemberId(auth.principalId());req.setStudentId(null);req.setAuthKey(null);}
        return ResponseEntity.ok(consultationService.close(academyId, id, req));
    }

    // ===== 읽음 처리 =====

    /**
     * 메시지 읽음 처리 (상대방 메시지만, 멱등).
     * body: {studentId|authKey(학부모) 또는 memberId(원장·강사)}
     */
    @PostMapping("/{id}/messages/{messageId}/read")
    public ResponseEntity<ConsultationDto.ReadResponse> markMessageRead(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @PathVariable Long messageId,
            @RequestBody ConsultationDto.ReadRequest req) {
        var auth=com.rea.academy.security.AuthContextHolder.getOrNull();
        if(auth!=null && !auth.isParent()) {req.setMemberId(auth.principalId());req.setStudentId(null);req.setAuthKey(null);}
        return ResponseEntity.ok(
                consultationService.markMessageRead(academyId, id, messageId, req));
    }
}
