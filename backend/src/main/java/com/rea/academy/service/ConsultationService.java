package com.rea.academy.service;

import com.rea.academy.domain.consultation.ConsultationStatus;
import com.rea.academy.domain.consultation.ConsultationType;
import com.rea.academy.domain.consultation.ReaConsultation;
import com.rea.academy.domain.consultation.ReaConsultationMessage;
import com.rea.academy.domain.consultation.ReaConsultationMessageRepository;
import com.rea.academy.domain.consultation.ReaConsultationRepository;
import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.domain.notification.NotificationType;
import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.dto.NotificationDto;
import com.rea.academy.security.ParentScope;
import com.rea.academy.security.ReaForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 상담 관리 서비스 (5단계).
 * - 상담 스레드 생성: INQUIRY(학부모 문의) / NEWS(학원→학부모 1:1 아이소식)
 * - 스레드 내 메시지 작성 (텍스트, 첨부파일 메타데이터)
 * - 문의 모음: 미답변(OPEN)/답변완료(ANSWERED) 필터 조회·답변
 * - 학원 발신 메시지 → 학부모에게 CONSULT_REPLY 알림 발송 (NotificationService 재사용, 실패 격리)
 * - 메시지 읽음 처리 (상대방 메시지만, 멱등)
 * - 스레드 종료: OPEN/ANSWERED → CLOSED
 *
 * 상태 전이:
 * - INQUIRY: 생성 OPEN → 학원 답변 시 ANSWERED → 학부모 추가 문의 시 OPEN → 종료 시 CLOSED
 * - NEWS: 생성 OPEN (학원 발신) → 종료 전까지 OPEN 유지 (학부모 답글 가능, 양방향)
 *
 * 격리 규칙: 모든 조회·수정은 academyId로 스코핑. 타 학원 데이터 접근 불가.
 * 화면-기능 분리: 순수 업무 로직. UI 프레임워크 의존 없음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsultationService {

    private final ReaConsultationRepository consultationRepository;
    private final ReaConsultationMessageRepository messageRepository;
    private final ReaStudentRepository studentRepository;
    private final ReaMemberRepository memberRepository;
    private final NotificationService notificationService;

    /** 알림용 내용 최대 길이 (rea_notifications.content 1000자 제한 고려) */
    private static final int NOTIFICATION_CONTENT_MAX = 900;

    // ===== 스레드 생성 =====

    /**
     * 상담 스레드 생성.
     * - INQUIRY: 학부모가 시작. content가 있으면 첫 문의 메시지로 함께 저장.
     * - NEWS: 학원(원장·강사)이 시작. authorMemberId·content 필수, 첫 메시지로 저장하고
     *   해당 학부모에게 CONSULT_REPLY 알림을 발송한다 (실패 격리).
     */
    @Transactional
    public ConsultationDto.CreateResponse create(Long academyId, ConsultationDto.CreateRequest req) {
        // 학부모(학생 키 로그인)는 본인(자녀) 명의로만 INQUIRY 생성 가능.
        // NEWS(학원 발신 아이소식)는 원장·강사 전용 — 학부모의 작성자 위조 차단.
        boolean isParentCaller = ParentScope.ownStudentIdOrNull() != null;
        if (isParentCaller) {
            ConsultationType requested = ConsultationType.parse(req.getType());
            if (requested != ConsultationType.INQUIRY) {
                throw new ReaForbiddenException("학부모는 문의(INQUIRY)만 등록할 수 있습니다.");
            }
        }
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(
                req.getStudentId(), req.getAuthKey());
        ConsultationType type = ConsultationType.parse(req.getType());
        ReaStudent student = requireStudent(academyId, ref.studentId(), ref.authKey());
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("제목은 필수입니다.");
        }

        ReaConsultation consultation = consultationRepository.save(ReaConsultation.builder()
                .academyId(academyId)
                .studentId(student.getId())
                .studentName(student.getName())
                .type(type)
                .status(ConsultationStatus.OPEN)
                .title(req.getTitle().trim())
                .build());

        ConsultationDto.CreateMessageResponse firstMessage = null;
        if (type == ConsultationType.NEWS) {
            if (req.getAuthorMemberId() == null) {
                throw new IllegalArgumentException("아이소식 작성자(원장·강사)는 필수입니다.");
            }
            if (req.getContent() == null || req.getContent().isBlank()) {
                throw new IllegalArgumentException("아이소식 내용은 필수입니다.");
            }
            ReaMember author = requireStaff(academyId, req.getAuthorMemberId());
            firstMessage = saveMessageInternal(academyId, consultation, null, author,
                    req.getContent().trim(),
                    req.getAttachmentFileName(), req.getAttachmentFileKey(), true);
        } else if (req.getContent() != null && !req.getContent().isBlank()) {
            firstMessage = saveMessageInternal(academyId, consultation, student, null,
                    req.getContent().trim(),
                    req.getAttachmentFileName(), req.getAttachmentFileKey(), false);
        }

        ConsultationDto.CreateResponse.CreateResponseBuilder resp =
                ConsultationDto.CreateResponse.builder()
                        .consultation(toDetailResponse(consultation,
                                type == ConsultationType.INQUIRY, null));
        if (firstMessage != null) {
            resp.firstMessageId(firstMessage.getMessage().getId())
                    .notificationSentCount(firstMessage.getNotificationSentCount())
                    .notificationFailedCount(firstMessage.getNotificationFailedCount())
                    .notificationSkippedCount(firstMessage.getNotificationSkippedCount())
                    .notificationError(firstMessage.getNotificationError());
        }
        return resp.build();
    }

    // ===== 목록 =====

    /**
     * 상담 스레드 목록.
     * - 학부모 (studentId 또는 authKey): 본인(자녀) 스레드만. status·type 필터 가능.
     * - 관리자: 학원 전체 스레드. status·type·targetStudentId 필터 가능.
     */
    public List<ConsultationDto.ListResponse> list(
            Long academyId, String status, String type,
            Long studentId, String authKey, Long targetStudentId) {
        ConsultationStatus statusFilter = blankToNull(status) == null
                ? null : ConsultationStatus.parse(status);
        ConsultationType typeFilter = blankToNull(type) == null
                ? null : ConsultationType.parse(type);

        // 학부모는 X-Auth-Key만으로 본인 스레드 조회 (파라미터 위조 시 본인으로 강제).
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(studentId, authKey);
        studentId = ref.studentId();
        authKey = ref.authKey();

        List<ReaConsultation> consultations;
        if (isParentView(studentId, authKey)) {
            ReaStudent student = requireStudent(academyId, studentId, authKey);
            consultations = consultationRepository
                    .findByAcademyIdAndStudentIdAndActiveTrueOrderByIdDesc(academyId, student.getId());
        } else {
            consultations = consultationRepository
                    .findByAcademyIdAndActiveTrueOrderByIdDesc(academyId);
            if (targetStudentId != null) {
                ReaStudent target = requireStudent(academyId, targetStudentId, null);
                consultations = consultations.stream()
                        .filter(c -> c.getStudentId().equals(target.getId()))
                        .collect(Collectors.toList());
            }
        }
        return consultations.stream()
                .filter(c -> statusFilter == null || c.getStatus() == statusFilter)
                .filter(c -> typeFilter == null || c.getType() == typeFilter)
                .map(this::toListResponse)
                .collect(Collectors.toList());
    }

    // ===== 상세 =====

    /**
     * 스레드 상세 + 메시지 목록 (작성순).
     * - 학부모 (studentId 또는 authKey): 본인(자녀) 스레드만 조회 가능.
     * - 관리자: 전체 조회 가능.
     * unreadCount는 조회자 기준: 학부모=학원 미읽음 메시지, 관리자=학부모 미읽음 메시지.
     */
    public ConsultationDto.DetailResponse detail(
            Long academyId, Long consultationId, Long studentId, String authKey) {
        ReaConsultation consultation = requireConsultation(academyId, consultationId);
        // 학부모는 X-Auth-Key만으로 본인 스레드 조회 (타 학생 스레드 위조 차단).
        ParentScope.StudentRef ref = ParentScope.forceOwnStudent(studentId, authKey);
        studentId = ref.studentId();
        authKey = ref.authKey();
        boolean parentView = isParentView(studentId, authKey);
        Long viewerStudentId = null;
        if (parentView) {
            ReaStudent student = requireStudent(academyId, studentId, authKey);
            if (!consultation.getStudentId().equals(student.getId())) {
                throw new IllegalArgumentException("조회할 수 없는 상담입니다.");
            }
            viewerStudentId = student.getId();
        }
        return toDetailResponse(consultation, parentView, viewerStudentId);
    }

    // ===== 메시지 작성 =====

    /**
     * 스레드 내 메시지 작성.
     * - 학부모 (studentId 또는 authKey): 해당 스레드의 학생(학부모)만 작성 가능.
     * - 학원 (memberId): 같은 학원의 활성 원장·강사만 작성 가능. 작성 시 해당 학부모에게
     *   CONSULT_REPLY 알림을 발송한다 (실패 격리).
     * - 종료된 스레드(CLOSED)에는 작성 불가 (409).
     */
    @Transactional
    public ConsultationDto.CreateMessageResponse addMessage(
            Long academyId, Long consultationId, ConsultationDto.MessageRequest req) {
        ReaConsultation consultation = requireConsultation(academyId, consultationId);
        if (req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("내용은 필수입니다.");
        }
        ReaStudent parent = null;
        ReaMember staff = null;
        if (req.getMemberId() != null) {
            staff = requireStaff(academyId, req.getMemberId());
        } else {
            parent = requireStudent(academyId, req.getStudentId(), req.getAuthKey());
            if (!parent.getId().equals(consultation.getStudentId())) {
                throw new IllegalArgumentException("해당 상담의 학부모만 메시지를 작성할 수 있습니다.");
            }
        }
        return saveMessageInternal(academyId, consultation, parent, staff,
                req.getContent().trim(),
                req.getAttachmentFileName(), req.getAttachmentFileKey(), true);
    }

    // ===== 스레드 종료 =====

    /**
     * 스레드 종료 (OPEN/ANSWERED → CLOSED).
     * 종료자: 학원(원장·강사, memberId) 또는 해당 학부모(studentId/authKey).
     * 이미 종료된 스레드는 409.
     */
    @Transactional
    public ConsultationDto.DetailResponse close(
            Long academyId, Long consultationId, ConsultationDto.CloseRequest req) {
        ReaConsultation consultation = requireConsultation(academyId, consultationId);
        if (consultation.getStatus() == ConsultationStatus.CLOSED) {
            throw new IllegalStateException("이미 종료된 상담입니다.");
        }
        boolean parentView;
        Long viewerStudentId;
        if (req.getMemberId() != null) {
            requireStaff(academyId, req.getMemberId());
            parentView = false;
            viewerStudentId = null;
        } else {
            ReaStudent parent = requireStudent(academyId, req.getStudentId(), req.getAuthKey());
            if (!parent.getId().equals(consultation.getStudentId())) {
                throw new IllegalArgumentException("해당 상담의 학부모만 종료할 수 있습니다.");
            }
            parentView = true;
            viewerStudentId = parent.getId();
        }
        consultation.close();
        return toDetailResponse(consultation, parentView, viewerStudentId);
    }

    // ===== 읽음 처리 =====

    /**
     * 메시지 읽음 처리.
     * - 학부모 (studentId 또는 authKey): 본인 스레드의 학원 메시지만.
     * - 학원 (memberId): 같은 학원의 활성 원장·강사. 학부모 메시지만.
     * 본인이 작성한 메시지는 읽음 처리할 수 없다 (400). 멱등.
     */
    @Transactional
    public ConsultationDto.ReadResponse markMessageRead(
            Long academyId, Long consultationId, Long messageId,
            ConsultationDto.ReadRequest req) {
        ReaConsultation consultation = requireConsultation(academyId, consultationId);
        ReaConsultationMessage message = messageRepository.findById(messageId)
                .filter(m -> m.getAcademyId().equals(academyId)
                        && m.getConsultationId().equals(consultationId))
                .orElseThrow(() -> new IllegalArgumentException("메시지를 찾을 수 없습니다."));

        Long readerStudentId = null;
        Long readerMemberId = null;
        String readerName;
        if (req.getMemberId() != null) {
            ReaMember staff = requireStaff(academyId, req.getMemberId());
            readerMemberId = staff.getId();
            readerName = staff.getName();
        } else {
            ReaStudent parent = requireStudent(academyId, req.getStudentId(), req.getAuthKey());
            if (!parent.getId().equals(consultation.getStudentId())) {
                throw new IllegalArgumentException("해당 상담의 참여자만 읽음 처리할 수 있습니다.");
            }
            readerStudentId = parent.getId();
            readerName = parent.getParentName() != null && !parent.getParentName().isBlank()
                    ? parent.getParentName() : parent.getName();
        }

        if ((readerStudentId != null && readerStudentId.equals(message.getSenderStudentId()))
                || (readerMemberId != null && readerMemberId.equals(message.getSenderMemberId()))) {
            throw new IllegalArgumentException("본인이 작성한 메시지는 읽음 처리할 수 없습니다.");
        }

        boolean already = message.isRead();
        if (!already) {
            message.markRead();
        }
        return ConsultationDto.ReadResponse.builder()
                .consultationId(consultationId)
                .messageId(messageId)
                .readerName(readerName)
                .readAt(message.getReadAt())
                .alreadyRead(already)
                .build();
    }

    // ===== 내부: 메시지 저장 + 상태 전이 + 알림 =====

    /**
     * 메시지 저장 공통 처리.
     * @param notifyParent 학원 발신 시 학부모에게 CONSULT_REPLY 알림 발송 여부
     */
    private ConsultationDto.CreateMessageResponse saveMessageInternal(
            Long academyId, ReaConsultation consultation,
            ReaStudent parent, ReaMember staff,
            String content, String attachmentFileName, String attachmentFileKey,
            boolean notifyParent) {
        if (consultation.getStatus() == ConsultationStatus.CLOSED) {
            throw new IllegalStateException("종료된 상담에는 메시지를 작성할 수 없습니다.");
        }

        String senderName;
        ReaConsultationMessage.ReaConsultationMessageBuilder builder =
                ReaConsultationMessage.builder()
                        .academyId(academyId)
                        .consultationId(consultation.getId())
                        .content(content)
                        .attachmentFileName(blankToNull(attachmentFileName))
                        .attachmentFileKey(blankToNull(attachmentFileKey));
        if (staff != null) {
            builder.senderMemberId(staff.getId());
            senderName = staff.getName();
        } else {
            builder.senderStudentId(parent.getId());
            senderName = parent.getParentName() != null && !parent.getParentName().isBlank()
                    ? parent.getParentName() : parent.getName();
        }
        builder.senderName(senderName);
        ReaConsultationMessage saved = messageRepository.save(builder.build());

        // 상태 전이 (INQUIRY만: 학원 답변→ANSWERED, 학부모 추가 문의→OPEN. NEWS는 OPEN 유지)
        if (consultation.getType() == ConsultationType.INQUIRY) {
            if (staff != null && consultation.getStatus() == ConsultationStatus.OPEN) {
                consultation.markAnswered();
            } else if (parent != null && consultation.getStatus() == ConsultationStatus.ANSWERED) {
                consultation.reopen();
            }
        }

        // 학원 발신 시 학부모에게 CONSULT_REPLY 알림 발송 (실패 격리)
        ConsultationDto.CreateMessageResponse.CreateMessageResponseBuilder resp =
                ConsultationDto.CreateMessageResponse.builder()
                        .message(toMessageResponse(saved));
        if (staff != null && notifyParent) {
            try {
                NotificationDto.SendResponse sendResp = notificationService.sendToStudent(
                        academyId,
                        consultation.getStudentId(),
                        NotificationType.CONSULT_REPLY,
                        "[상담 답변] " + consultation.getTitle(),
                        truncate(content, NOTIFICATION_CONTENT_MAX));
                resp.notificationSentCount(sendResp.getSentCount())
                        .notificationFailedCount(sendResp.getFailedCount())
                        .notificationSkippedCount(sendResp.getSkippedCount());
            } catch (RuntimeException e) {
                resp.notificationError("알림 발송 중 오류: " + e.getMessage());
            }
        }
        return resp.build();
    }

    // ===== 내부 헬퍼 =====

    private ReaConsultation requireConsultation(Long academyId, Long consultationId) {
        return consultationRepository.findById(consultationId)
                .filter(c -> c.getActive() && c.getAcademyId().equals(academyId))
                .orElseThrow(() -> new IllegalArgumentException("상담을 찾을 수 없습니다."));
    }

    /** 학생 조회: studentId 또는 authKey 중 하나로 같은 학원의 활성 학생을 찾는다. */
    private ReaStudent requireStudent(Long academyId, Long studentId, String authKey) {
        if (studentId != null) {
            return studentRepository.findById(studentId)
                    .filter(s -> s.getActive() && s.getAcademyId().equals(academyId))
                    .orElseThrow(() -> new IllegalArgumentException("학생을 찾을 수 없습니다."));
        }
        if (authKey != null && !authKey.isBlank()) {
            return studentRepository.findByAuthKeyAndActiveTrue(authKey.trim())
                    .filter(s -> s.getAcademyId().equals(academyId))
                    .orElseThrow(() -> new IllegalArgumentException("인증키로 학생을 찾을 수 없습니다."));
        }
        throw new IllegalArgumentException("studentId 또는 authKey가 필요합니다.");
    }

    /** 학원 스태프 조회: 같은 학원의 활성 원장·강사 회원 */
    private ReaMember requireStaff(Long academyId, Long memberId) {
        return memberRepository.findById(memberId)
                .filter(m -> m.getActive() && m.getAcademyId().equals(academyId)
                        && (m.getRole() == ReaRole.ACADEMY_OWNER
                        || m.getRole() == ReaRole.TEACHER))
                .orElseThrow(() -> new IllegalArgumentException(
                        "작성자를 찾을 수 없습니다 (원장·강사 회원만 작성 가능)."));
    }

    /** studentId/authKey가 주어지면 학부모 조회, 없으면 관리자 조회 */
    private boolean isParentView(Long studentId, String authKey) {
        return studentId != null || (authKey != null && !authKey.isBlank());
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    // ===== DTO 변환 =====

    private ConsultationDto.ListResponse toListResponse(ReaConsultation c) {
        return ConsultationDto.ListResponse.builder()
                .id(c.getId())
                .academyId(c.getAcademyId())
                .studentId(c.getStudentId())
                .studentName(c.getStudentName())
                .type(c.getType().name())
                .typeLabel(c.getType().getLabel())
                .status(c.getStatus().name())
                .statusLabel(c.getStatus().getLabel())
                .title(c.getTitle())
                .messageCount(messageRepository.countByAcademyIdAndConsultationId(
                        c.getAcademyId(), c.getId()))
                .lastMessageAt(messageRepository
                        .findFirstByAcademyIdAndConsultationIdOrderByIdDesc(
                                c.getAcademyId(), c.getId())
                        .map(ReaConsultationMessage::getCreatedAt).orElse(null))
                .createdAt(c.getCreatedAt())
                .build();
    }

    private ConsultationDto.DetailResponse toDetailResponse(
            ReaConsultation c, boolean parentView, Long viewerStudentId) {
        List<ReaConsultationMessage> messages = messageRepository
                .findByAcademyIdAndConsultationIdOrderByIdAsc(c.getAcademyId(), c.getId());
        long unreadCount = messages.stream()
                .filter(m -> !m.isRead())
                // 학부모 조회: 학원(STAFF) 메시지 중 미읽음 / 관리자 조회: 학부모 메시지 중 미읽음
                .filter(m -> parentView ? !m.isFromParent() : m.isFromParent())
                .count();
        return ConsultationDto.DetailResponse.builder()
                .id(c.getId())
                .academyId(c.getAcademyId())
                .studentId(c.getStudentId())
                .studentName(c.getStudentName())
                .type(c.getType().name())
                .typeLabel(c.getType().getLabel())
                .status(c.getStatus().name())
                .statusLabel(c.getStatus().getLabel())
                .title(c.getTitle())
                .messageCount(messages.size())
                .unreadCount(unreadCount)
                .messages(messages.stream()
                        .map(this::toMessageResponse)
                        .collect(Collectors.toList()))
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private ConsultationDto.MessageResponse toMessageResponse(ReaConsultationMessage m) {
        return ConsultationDto.MessageResponse.builder()
                .id(m.getId())
                .consultationId(m.getConsultationId())
                .senderKind(m.isFromParent() ? "PARENT" : "STAFF")
                .senderName(m.getSenderName())
                .content(m.getContent())
                .attachmentFileName(m.getAttachmentFileName())
                .attachmentFileKey(m.getAttachmentFileKey())
                .read(m.isRead())
                .readAt(m.getReadAt())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
