package com.rea.academy.service;

import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.domain.admin.ReaAuditLog;
import com.rea.academy.domain.admin.ReaAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 기록기 (8단계).
 * 변경 이력을 rea_audit_logs에 append-only로 기록한다. 수정·삭제 기능 없음.
 * 기존 서비스의 변경 사항은 AuditLogAspect(AOP)가 이 기록기를 호출하므로
 * 기존 코드를 수정하지 않고도 이력이 남는다.
 */
@Service
@RequiredArgsConstructor
public class AuditRecorder {

    private final ReaAuditLogRepository auditLogRepository;

    /**
     * 변경 이력 1건 기록.
     *
     * @param academyId  소속 학원 ID (필수)
     * @param actionType 행위 종류 (필수)
     * @param targetType 변경 대상 종류 (학생/반/출결/공지/회원/학원, 선택)
     * @param targetId   변경 대상 ID (선택)
     * @param targetName 변경 대상 이름 스냅샷 (선택)
     * @param actorId    행위자 회원 ID (시스템/미상 시 null)
     * @param actorName  행위자 이름 (시스템/미상 시 null)
     * @param detail     상세 설명 (선택)
     */
    @Transactional
    public void record(Long academyId, AuditActionType actionType, String targetType,
                       Long targetId, String targetName,
                       Long actorId, String actorName, String detail) {
        if (academyId == null) {
            throw new IllegalArgumentException("academyId는 필수입니다.");
        }
        if (actionType == null) {
            throw new IllegalArgumentException("actionType은 필수입니다.");
        }
        auditLogRepository.save(ReaAuditLog.builder()
                .academyId(academyId)
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .actorId(actorId)
                .actorName(actorName)
                .detail(detail)
                .build());
    }
}
