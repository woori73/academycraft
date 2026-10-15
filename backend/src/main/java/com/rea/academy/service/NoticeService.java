package com.rea.academy.service;

import com.rea.academy.domain.clazz.ClassEnrollmentRepository;
import com.rea.academy.domain.clazz.ReaClass;
import com.rea.academy.domain.clazz.ReaClassRepository;
import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.domain.notice.ReaNotice;
import com.rea.academy.domain.notice.ReaNoticeRead;
import com.rea.academy.domain.notice.ReaNoticeReadRepository;
import com.rea.academy.domain.notice.ReaNoticeRepository;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.dto.NotificationDto;
import com.rea.academy.security.ParentScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 공지 관리 서비스 (4단계).
 * - 공지 작성·수정·삭제(비활성). 대상: 전체공지(classId=null) 또는 특정 반 공지
 * - 목록: 관리자(전체) / 학부모(전체공지 + 자녀 소속 반 공지)
 * - 상세: 조회수 +1. 조회수는 관리자 조회에서만 응답에 포함
 * - 읽음 확인: 학생(학부모)별 1행 기록 (멱등)
 * - 공지 등록 시 대상 학부모에게 NOTICE 알림 발송 (NotificationService 재사용, 실패 격리)
 *
 * 격리 규칙: 모든 조회·수정은 academyId로 스코핑. 타 학원 데이터 접근 불가.
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NoticeService {

    private final ReaNoticeRepository noticeRepository;
    private final ReaNoticeReadRepository readRepository;
    private final ReaStudentRepository studentRepository;
    private final ReaClassRepository classRepository;
    private final ClassEnrollmentRepository enrollmentRepository;
    private final ReaMemberRepository memberRepository;
    private final NotificationService notificationService;

    /** 알림용 내용 최대 길이 (rea_notifications.content 1000자 제한 고려) */
    private static final int NOTIFICATION_CONTENT_MAX = 900;

    // ===== 작성 =====

    /**
     * 공지 작성. 저장 후 대상 학부모에게 NOTICE 알림을 발송한다.
     * 알림 발송 실패는 공지 등록을 막지 않는다 (실패 격리).
     */
    @Transactional
    public NoticeDto.CreateResponse create(Long academyId, NoticeDto.CreateRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("제목은 필수입니다.");
        }
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("내용은 필수입니다.");
        }
        Long classId = null;
        if (req.getClassId() != null) {
            classId = requireActiveClass(academyId, req.getClassId()).getId();
        }
        Long authorId = null;
        String authorName = null;
        if (req.getAuthorId() != null) {
            ReaMember author = requireWriter(academyId, req.getAuthorId());
            authorId = author.getId();
            authorName = author.getName();
        }

        ReaNotice notice = ReaNotice.builder()
                .academyId(academyId)
                .classId(classId)
                .title(req.getTitle().trim())
                .content(req.getContent().trim())
                .authorId(authorId)
                .authorName(authorName)
                .attachmentFileName(blankToNull(req.getAttachmentFileName()))
                .attachmentFileKey(blankToNull(req.getAttachmentFileKey()))
                .build();
        notice = noticeRepository.save(notice);

        // 공지 등록 알림 발송 (3단계 NotificationService 재사용, 실패 격리)
        NoticeDto.CreateResponse.CreateResponseBuilder resp = NoticeDto.CreateResponse.builder()
                .notice(toDetailResponse(academyId, notice, null));
        try {
            NotificationDto.SendRequest sendReq = NotificationDto.SendRequest.builder()
                    .targetType(notice.isAllNotice() ? "ALL" : "CLASS")
                    .targetIds(notice.isAllNotice() ? null : List.of(notice.getClassId()))
                    .title("[공지] " + notice.getTitle())
                    .content(truncate(notice.getContent(), NOTIFICATION_CONTENT_MAX))
                    .build();
            NotificationDto.SendResponse sendResp = notificationService.sendMessage(academyId, sendReq);
            resp.notificationSentCount(sendResp.getSentCount())
                    .notificationFailedCount(sendResp.getFailedCount())
                    .notificationSkippedCount(sendResp.getSkippedCount());
        } catch (RuntimeException e) {
            resp.notificationError("알림 발송 중 오류: " + e.getMessage());
        }
        return resp.build();
    }

    // ===== 목록 =====

    /**
     * 공지 목록.
     * - 관리자 (studentId/authKey 없음): 학원 전체 공지. scope·classId로 필터 가능.
     * - 학부모 (studentId 또는 authKey): 전체공지 + 자녀 소속 반 공지 통합 조회.
     *   scope=ALL이면 전체공지만, scope=CLASS이면 반소식만, classId가 있으면 해당 반(자녀 소속)만.
     */
    public List<NoticeDto.ListResponse> list(
            Long academyId, String scope, Long classId, Long studentId, String authKey) {
        String normalizedScope = parseScope(scope);
        // NTC-04: 학생 키(X-Auth-Key) 로그인이면 쿼리 파라미터 없이도 자동으로 학부모 뷰.
        // 학부모가 타 학생 ID/키를 담아 보내도 본인 것으로 강제 치환된다 (하위 호환: 원장·강사는 파라미터 그대로).
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(studentId, authKey);
        studentId = ref.studentId();
        authKey = ref.authKey();
        if (isParentView(studentId, authKey)) {
            ReaStudent student = requireStudent(academyId, studentId, authKey);
            List<Long> enrolledClassIds = enrollmentRepository
                    .findByStudentIdAndActiveTrue(student.getId()).stream()
                    .map(e -> classRepository.findById(e.getClassId()).orElse(null))
                    .filter(c -> c != null && c.getAcademyId().equals(academyId) && c.getActive())
                    .map(ReaClass::getId)
                    .collect(Collectors.toList());
            if (classId != null && !enrolledClassIds.contains(classId)) {
                throw new IllegalArgumentException("자녀 소속 반의 공지만 조회할 수 있습니다.");
            }
            List<ReaNotice> notices = enrolledClassIds.isEmpty()
                    ? noticeRepository.findByAcademyIdAndClassIdIsNullAndActiveTrueOrderByIdDesc(academyId)
                    : noticeRepository.findVisibleNotices(academyId, enrolledClassIds);
            return notices.stream()
                    .filter(n -> matchesScope(n, normalizedScope))
                    .filter(n -> classId == null || classId.equals(n.getClassId()))
                    .map(n -> toListResponse(n, null))
                    .collect(Collectors.toList());
        }
        // 관리자 조회
        if (classId != null) {
            requireClass(academyId, classId);
        }
        List<ReaNotice> notices = classId != null
                ? noticeRepository.findByAcademyIdAndClassIdAndActiveTrueOrderByIdDesc(academyId, classId)
                : noticeRepository.findByAcademyIdAndActiveTrueOrderByIdDesc(academyId);
        return notices.stream()
                .filter(n -> matchesScope(n, normalizedScope))
                .map(n -> toListResponse(n, n.getViewCount()))
                .collect(Collectors.toList());
    }

    // ===== 상세 =====

    /**
     * 공지 상세. 조회할 때마다 조회수 +1.
     * 조회수는 관리자 조회에서만 응답에 포함된다 (학부모 조회에서는 null).
     * 학부모는 전체공지 또는 자녀 소속 반의 공지만 볼 수 있다.
     */
    @Transactional
    public NoticeDto.DetailResponse detail(
            Long academyId, Long noticeId, Long studentId, String authKey) {
        // NTC-04: X-Auth-Key가 학생 키이면 자동으로 학부모 뷰 (읽음확인 버튼 표시용 myRead 포함).
        // 쿼리 파라미터 방식은 원장·강사 미리보기용으로 하위 호환 유지.
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(studentId, authKey);
        studentId = ref.studentId();
        authKey = ref.authKey();
        ReaNotice notice = requireNotice(academyId, noticeId);
        Long viewerStudentId = null;
        if (isParentView(studentId, authKey)) {
            ReaStudent student = requireStudent(academyId, studentId, authKey);
            viewerStudentId = student.getId();
            if (!isVisibleToStudent(notice, student.getId())) {
                throw new IllegalArgumentException("조회할 수 없는 공지입니다.");
            }
        }
        notice.increaseViewCount();
        boolean isAdmin = !isParentView(studentId, authKey);
        return toDetailResponse(academyId, notice,
                isAdmin ? notice.getViewCount() : null, viewerStudentId);
    }

    // ===== 수정·삭제 =====

    /** 공지 수정. null인 항목은 변경하지 않는다. */
    @Transactional
    public NoticeDto.DetailResponse update(
            Long academyId, Long noticeId, NoticeDto.UpdateRequest req) {
        ReaNotice notice = requireNotice(academyId, noticeId);
        if (req.getTitle() != null && req.getTitle().isBlank()) {
            throw new IllegalArgumentException("제목은 비어 있을 수 없습니다.");
        }
        if (req.getContent() != null && req.getContent().isBlank()) {
            throw new IllegalArgumentException("내용은 비어 있을 수 없습니다.");
        }
        Long classId = null;
        boolean changeToAll = Boolean.TRUE.equals(req.getChangeToAll());
        if (!changeToAll && req.getClassId() != null) {
            classId = requireActiveClass(academyId, req.getClassId()).getId();
        }
        notice.update(trimOrNull(req.getTitle()), trimOrNull(req.getContent()),
                classId, changeToAll,
                req.getAttachmentFileName(), req.getAttachmentFileKey());
        return toDetailResponse(academyId, notice, notice.getViewCount(), null);
    }

    /** 공지 삭제 (비활성). 목록·상세에서 제외된다. */
    @Transactional
    public void delete(Long academyId, Long noticeId) {
        ReaNotice notice = requireNotice(academyId, noticeId);
        notice.deactivate();
    }

    // ===== 읽음 확인 =====

    /**
     * 읽음 확인. 학생(학부모)이 공지를 읽었음을 기록한다. 멱등 (중복 호출 시 기존 기록 반환).
     * 학부모는 전체공지 또는 자녀 소속 반의 공지만 읽음 확인할 수 있다.
     */
    @Transactional
    public NoticeDto.ReadResponse markRead(
            Long academyId, Long noticeId, NoticeDto.ReadRequest req) {
        ReaNotice notice = requireNotice(academyId, noticeId);
        // 학부모는 본인(자녀)에 대해서만 읽음 확인 가능 — 타 학생 ID/키 위조 차단.
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(
                req.getStudentId(), req.getAuthKey());
        ReaStudent student = requireStudent(academyId, ref.studentId(), ref.authKey());
        if (!isVisibleToStudent(notice, student.getId())) {
            throw new IllegalArgumentException("읽음 확인할 수 없는 공지입니다.");
        }
        return readRepository.findByAcademyIdAndNoticeIdAndStudentId(
                        academyId, noticeId, student.getId())
                .map(existing -> NoticeDto.ReadResponse.builder()
                        .noticeId(noticeId)
                        .studentId(student.getId())
                        .studentName(student.getName())
                        .readAt(existing.getReadAt())
                        .alreadyRead(true)
                        .build())
                .orElseGet(() -> {
                    ReaNoticeRead saved = readRepository.save(ReaNoticeRead.builder()
                            .academyId(academyId)
                            .noticeId(noticeId)
                            .studentId(student.getId())
                            .build());
                    return NoticeDto.ReadResponse.builder()
                            .noticeId(noticeId)
                            .studentId(student.getId())
                            .studentName(student.getName())
                            .readAt(saved.getReadAt())
                            .alreadyRead(false)
                            .build();
                });
    }

    /** 확인자 목록 (관리자): 공지를 읽은 학생 목록. 읽음 시각순. */
    public List<NoticeDto.ReaderResponse> readers(Long academyId, Long noticeId) {
        requireNotice(academyId, noticeId);
        return readRepository.findByAcademyIdAndNoticeIdOrderByReadAtAsc(academyId, noticeId)
                .stream()
                .map(r -> NoticeDto.ReaderResponse.builder()
                        .studentId(r.getStudentId())
                        .studentName(studentRepository.findById(r.getStudentId())
                                .map(ReaStudent::getName).orElse("알 수 없음"))
                        .readAt(r.getReadAt())
                        .build())
                .collect(Collectors.toList());
    }

    // ===== 내부 헬퍼 =====

    private ReaNotice requireNotice(Long academyId, Long noticeId) {
        return noticeRepository.findById(noticeId)
                .filter(n -> n.getActive() && n.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("공지를 찾을 수 없습니다."));
    }

    /** 반 조회 (소유권만 확인 — 목록 필터용) */
    private ReaClass requireClass(Long academyId, Long classId) {
        return classRepository.findById(classId)
                .filter(c -> c.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("반을 찾을 수 없습니다: " + classId));
    }

    /** 공지 대상으로 쓸 수 있는 활성 반 */
    private ReaClass requireActiveClass(Long academyId, Long classId) {
        ReaClass clazz = requireClass(academyId, classId);
        if (!clazz.getActive()) {
            throw new IllegalArgumentException("종료된 반에는 공지를 등록할 수 없습니다: " + classId);
        }
        return clazz;
    }

    /** 작성자: 같은 학원의 활성 원장·강사 회원 */
    private ReaMember requireWriter(Long academyId, Long authorId) {
        return memberRepository.findById(authorId)
                .filter(m -> m.getActive() && m.getAcademyId().equals(academyId)
                        && (m.getRole() == ReaRole.ACADEMY_OWNER || m.getRole() == ReaRole.TEACHER))
                .orElseThrow(() -> new IllegalArgumentException(
                        "작성자를 찾을 수 없습니다 (원장·강사 회원만 작성 가능)."));
    }

    /** 학생 조회: studentId 또는 authKey 중 하나로 같은 학원의 활성 학생을 찾는다. */
    private ReaStudent requireStudent(Long academyId, Long studentId, String authKey) {
        if (studentId != null) {
            return studentRepository.findById(studentId)
                    .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                    .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));
        }
        if (authKey != null && !authKey.isBlank()) {
            return studentRepository.findByAuthKeyAndActiveTrue(authKey)
                    .filter(s -> s.getAcademyId().equals(academyId))
                    .orElseThrow(() -> new IllegalArgumentException("인증키로 학생을 찾을 수 없습니다."));
        }
        throw new IllegalArgumentException("studentId 또는 authKey가 필요합니다.");
    }

    /** studentId/authKey가 주어지면 학부모 조회, 없으면 관리자 조회 */
    private boolean isParentView(Long studentId, String authKey) {
        return studentId != null || (authKey != null && !authKey.isBlank());
    }

    /** 학부모에게 보이는 공지인지: 전체공지 또는 소속 반 공지 */
    private boolean isVisibleToStudent(ReaNotice notice, Long studentId) {
        if (notice.isAllNotice()) {
            return true;
        }
        return enrollmentRepository
                .findByClassIdAndStudentIdAndActiveTrue(notice.getClassId(), studentId)
                .isPresent();
    }

    /** scope: null이면 전체, ALL/CLASS (대소문자 무시). 그 외는 400. */
    private String parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        if (NoticeDto.SCOPE_ALL.equalsIgnoreCase(scope.trim())) {
            return NoticeDto.SCOPE_ALL;
        }
        if (NoticeDto.SCOPE_CLASS.equalsIgnoreCase(scope.trim())) {
            return NoticeDto.SCOPE_CLASS;
        }
        throw new IllegalArgumentException("scope는 ALL 또는 CLASS만 가능합니다.");
    }

    private boolean matchesScope(ReaNotice notice, String scope) {
        if (scope == null) {
            return true;
        }
        return NoticeDto.SCOPE_ALL.equals(scope) == notice.isAllNotice();
    }

    private String scopeOf(ReaNotice notice) {
        return notice.isAllNotice() ? NoticeDto.SCOPE_ALL : NoticeDto.SCOPE_CLASS;
    }

    private Map<Long, String> classNameMap(Long academyId) {
        return classRepository.findByAcademyIdOrderByIdAsc(academyId).stream()
                .collect(Collectors.toMap(ReaClass::getId, ReaClass::getName, (a, b) -> a));
    }

    private NoticeDto.ListResponse toListResponse(ReaNotice notice, Integer viewCount) {
        Map<Long, String> classNames = classNameMap(notice.getAcademyId());
        return NoticeDto.ListResponse.builder()
                .id(notice.getId())
                .academyId(notice.getAcademyId())
                .classId(notice.getClassId())
                .className(notice.getClassId() == null ? null : classNames.get(notice.getClassId()))
                .scope(scopeOf(notice))
                .title(notice.getTitle())
                .contentPreview(truncate(notice.getContent(), 100))
                .authorName(notice.getAuthorName())
                .viewCount(viewCount)
                .readCount(readRepository.countByAcademyIdAndNoticeId(
                        notice.getAcademyId(), notice.getId()))
                .createdAt(notice.getCreatedAt())
                .updatedAt(notice.getUpdatedAt())
                .build();
    }

    private NoticeDto.DetailResponse toDetailResponse(
            Long academyId, ReaNotice notice, Integer viewCount) {
        return toDetailResponse(academyId, notice, viewCount, null);
    }

    private NoticeDto.DetailResponse toDetailResponse(
            Long academyId, ReaNotice notice, Integer viewCount, Long viewerStudentId) {
        Map<Long, String> classNames = classNameMap(academyId);
        Boolean myRead = viewerStudentId == null ? null
                : readRepository.findByAcademyIdAndNoticeIdAndStudentId(
                        academyId, notice.getId(), viewerStudentId).isPresent();
        return NoticeDto.DetailResponse.builder()
                .id(notice.getId())
                .academyId(notice.getAcademyId())
                .classId(notice.getClassId())
                .className(notice.getClassId() == null ? null : classNames.get(notice.getClassId()))
                .scope(scopeOf(notice))
                .title(notice.getTitle())
                .content(notice.getContent())
                .authorName(notice.getAuthorName())
                .attachmentFileName(notice.getAttachmentFileName())
                .attachmentFileKey(notice.getAttachmentFileKey())
                .viewCount(viewCount)
                .readCount(readRepository.countByAcademyIdAndNoticeId(
                        notice.getAcademyId(), notice.getId()))
                .myRead(myRead)
                .createdAt(notice.getCreatedAt())
                .updatedAt(notice.getUpdatedAt())
                .build();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    private String trimOrNull(String text) {
        return text == null ? null : text.trim();
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
