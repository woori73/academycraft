package com.rea.academy.controller;

import com.rea.academy.dto.NotificationDto;
import com.rea.academy.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 알림 관리 API.
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 업무 로직은 Service에 위임.
 */
@RestController
@RequestMapping("/api/rea/{academyId}/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    // ===== 메시지 발송 (관리자) =====

    /**
     * 메시지 발송.
     * body: {targetType: INDIVIDUAL/CLASS/ALL, targetIds[], title, content}
     */
    @PostMapping("/send")
    public ResponseEntity<NotificationDto.SendResponse> sendMessage(
            @PathVariable Long academyId,
            @RequestBody NotificationDto.SendRequest req) {
        return ResponseEntity.ok(notificationService.sendMessage(academyId, req));
    }

    // ===== 알림 목록 (수신자 기준) =====

    /**
     * 수신자별 알림 목록.
     * ?studentId= 또는 ?authKey= (학부모는 학생 인증키로 조회)
     */
    @GetMapping
    public ResponseEntity<List<NotificationDto.NotificationResponse>> getNotifications(
            @PathVariable Long academyId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(
                notificationService.getNotifications(academyId, studentId, authKey));
    }

    /** 미읽음 건수: ?studentId= 또는 ?authKey= */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> countUnread(
            @PathVariable Long academyId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(
                Map.of("unreadCount", notificationService.countUnread(academyId, studentId, authKey)));
    }

    // ===== 발송 이력 (관리자) =====

    /** 발송 이력 조회 */
    @GetMapping("/history")
    public ResponseEntity<List<NotificationDto.HistoryResponse>> getHistory(
            @PathVariable Long academyId) {
        return ResponseEntity.ok(notificationService.getHistory(academyId));
    }

    // ===== 재시도 =====

    /** 실패 건 재시도 */
    @PostMapping("/{id}/retry")
    public ResponseEntity<NotificationDto.HistoryResponse> retry(
            @PathVariable Long academyId,
            @PathVariable Long id) {
        return ResponseEntity.ok(notificationService.retry(academyId, id));
    }

    // ===== 읽음 처리 =====

    /**
     * 알림 읽음 처리.
     * body: {studentId 또는 authKey} — 본인 알림만 가능
     */
    @PostMapping("/{id}/read")
    public ResponseEntity<NotificationDto.NotificationResponse> markRead(
            @PathVariable Long academyId,
            @PathVariable Long id,
            @RequestBody NotificationDto.ReadRequest req) {
        return ResponseEntity.ok(notificationService.markRead(academyId, id, req));
    }

    // ===== 알림 설정 =====

    /**
     * 알림 설정 조회.
     * ?studentId= 또는 ?authKey=
     */
    @GetMapping("/settings")
    public ResponseEntity<NotificationDto.SettingsResponse> getSettings(
            @PathVariable Long academyId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String authKey) {
        return ResponseEntity.ok(
                notificationService.getSettings(academyId, studentId, authKey));
    }

    /**
     * 알림 설정 변경.
     * body: {studentId 또는 authKey, settings: [{type, enabled}]}
     */
    @PutMapping("/settings")
    public ResponseEntity<NotificationDto.SettingsResponse> updateSettings(
            @PathVariable Long academyId,
            @RequestBody NotificationDto.SettingsRequest req) {
        return ResponseEntity.ok(notificationService.updateSettings(academyId, req));
    }
}
