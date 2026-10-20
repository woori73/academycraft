package com.rea.academy.service;

import com.rea.academy.domain.notice.ReaNotice;
import com.rea.academy.domain.notice.ReaNoticeRepository;
import com.rea.academy.security.AuthContext;
import com.rea.academy.security.ReaForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지 수정·삭제 소유권 판정.
 * - 원장: 소속 학원의 모든 공지.
 * - 일반 강사: 본인이 작성한 공지만. 다른 작성자·작성자 없음(author_id NULL) 공지는 403.
 * - 인증 컨텍스트 없음·학부모·타 학원: 거부(fail-closed).
 * 서비스(update/delete)는 AOP 감사 대상이라 시그니처를 바꾸지 않고, 컨트롤러가 호출 직전에 이 가드를 통과시킨다.
 */
@Component
@RequiredArgsConstructor
public class NoticeAccessGuard {

    static final String NOT_FOUND = "공지를 찾을 수 없습니다.";
    static final String TEACHER_OWN_ONLY = "본인이 작성한 공지만 수정·삭제할 수 있습니다.";

    private final ReaNoticeRepository noticeRepository;

    @Transactional(readOnly = true)
    public void checkCanModify(Long academyId, Long noticeId, AuthContext actor) {
        if (actor == null || actor.academyId() == null || !actor.academyId().equals(academyId)
                || actor.principalType() != AuthContext.PrincipalType.MEMBER) {
            throw new ReaForbiddenException("공지를 수정·삭제할 권한이 없습니다.");
        }
        ReaNotice notice = noticeRepository.findById(noticeId)
                .filter(n -> Boolean.TRUE.equals(n.getActive()) && n.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
        if (actor.isOwner()) {
            return;
        }
        if (actor.isTeacher() && notice.getAuthorId() != null && notice.getAuthorId().equals(actor.principalId())) {
            return;
        }
        throw new ReaForbiddenException(TEACHER_OWN_ONLY);
    }
}
