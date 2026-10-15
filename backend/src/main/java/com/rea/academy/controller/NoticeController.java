package com.rea.academy.controller;

import com.rea.academy.dto.NoticeDto;
import com.rea.academy.service.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 공지 관리 API (4단계).
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 * 조회수는 관리자 조회에서만 응답에 포함된다 (학부모 조회에서는 null).
 */
@RestController
@RequestMapping("/api/rea/{academyId}/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    // ===== 작성 (관리자) =====

    /**
     * 공지 작성.
     * body: {classId(선택, null이면 전체공지), title, content, authorId(선택), attachmentFileName/attachmentFileKey(선택)}
     * 저장 후 대상 학부모에게 NOTICE 알림이 발송된다.
     */
    @PostMapping
    public ResponseEntity<NoticeDto.CreateResponse> create(
            @PathVariable Long academyId,
            @RequestBody NoticeDto.CreateRequest req) {
        return ResponseEntity.ok(noticeService.create(academyId, req));
    }

    // ===== 목록 =====

    /**
     * 공지 목록.
     * ?scope=ALL|CLASS (선택), ?classId= (선택),
     * ?studentId= 또는 ?authKey= (학부모: 전체공지 + 자녀 소속 반 공지 통합 조회)
     */
    @GetMapping
    public ResponseEntity<List<NoticeDto.ListResponse>> list(
            @PathVariable Long academyId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(
                noticeService.list(academyId, scope, classId, studentId, authKey));
    }

    // ===== 상세 =====

    /**
     * 공지 상세. 조회할 때마다 조회수 +1.
     * ?studentId= 또는 ?authKey= (학부모: 조회수 미표시, myRead 포함)
     */
    @GetMapping("/{id}")
    public ResponseEntity<NoticeDto.DetailResponse> detail(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(
                noticeService.detail(academyId, id, studentId, authKey));
    }

    // ===== 수정·삭제 (관리자) =====

    /**
     * 공지 수정.
     * body: {title?, content?, classId?, changeToAll?(true면 전체공지로 전환), attachmentFileName?, attachmentFileKey?}
     */
    @PutMapping("/{id}")
    public ResponseEntity<NoticeDto.DetailResponse> update(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestBody NoticeDto.UpdateRequest req) {
        return ResponseEntity.ok(noticeService.update(academyId, id, req));
    }

    /** 공지 삭제 (비활성) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long academyId,
            @PathVariable Long id) {
        noticeService.delete(academyId, id);
        return ResponseEntity.noContent().build();
    }

    // ===== 읽음 확인 =====

    /**
     * 읽음 확인.
     * body: {studentId 또는 authKey} — 멱등
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<NoticeDto.ReadResponse> markRead(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestBody NoticeDto.ReadRequest req) {
        return ResponseEntity.ok(noticeService.markRead(academyId, id, req));
    }

    /**
     * 확인자 목록 (관리자): 공지를 읽은 학부모(학생) 목록
     */
    @GetMapping("/{id}/readers")
    public ResponseEntity<List<NoticeDto.ReaderResponse>> readers(
            @PathVariable Long academyId,
            @PathVariable Long id) {
        return ResponseEntity.ok(noticeService.readers(academyId, id));
    }
}
