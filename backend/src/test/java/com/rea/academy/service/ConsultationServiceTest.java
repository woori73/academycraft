package com.rea.academy.service;

import com.rea.academy.domain.consultation.ConsultationStatus;
import com.rea.academy.domain.consultation.ConsultationType;
import com.rea.academy.domain.notification.NotificationType;
import com.rea.academy.domain.notification.ReaNotification;
import com.rea.academy.domain.notification.ReaNotificationRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.ConsultationDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.NotificationDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 상담 관리 서비스 테스트 (H2 인메모리).
 * - 스레드 생성 (INQUIRY / NEWS) → 메시지 → 학원 답변(CONSULT_REPLY 알림) → 종료
 * - 읽음 처리 (상대방 메시지만, 멱등, 본인 메시지 400)
 * - 타입·상태별 조회, 학부모 본인 스레드만 조회
 * - 학원 격리
 * - 알림 OFF 시 skipped
 */
@SpringBootTest
@Transactional
class ConsultationServiceTest {

    @Autowired
    private ConsultationService consultationService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ReaNotificationRepository notificationRepository;

    private static final Long ACADEMY = 1L;
    private static final Long OTHER_ACADEMY = 2L;

    private Long teacherId;
    private Long ownerId;
    private Long studentId1; // 문의 주체 (학부모: 홍부모)
    private Long studentId2;
    private String authKey1;
    private Long otherTeacherId;
    private Long otherStudentId;

    @BeforeEach
    void setUp() {
        teacherId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.TEACHER)
                        .name("김강사").phone("010-1234-5678")
                        .build()).getId();
        ownerId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                        .name("박원장").phone("010-9999-8888")
                        .build()).getId();

        StudentDto.DetailResponse s1 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").grade("고2")
                        .parentName("홍부모").parentPhone("010-1111-2222")
                        .build());
        StudentDto.DetailResponse s2 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("박학생").grade("고1")
                        .parentName("박부모").parentPhone("010-3333-4444")
                        .build());
        studentId1 = s1.getId();
        studentId2 = s2.getId();
        authKey1 = s1.getAuthKey();

        otherTeacherId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER)
                        .name("타강사")
                        .build()).getId();
        otherStudentId = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학생")
                        .parentName("타부모")
                        .build()).getId();
    }

    private ConsultationDto.CreateRequest inquiryRequest(Long studentId, String title, String content) {
        return ConsultationDto.CreateRequest.builder()
                .studentId(studentId).type("INQUIRY").title(title).content(content)
                .build();
    }

    private long consultReplyCount(Long academyId) {
        return notificationRepository.findByAcademyIdOrderByCreatedAtDesc(academyId).stream()
                .filter(n -> n.getType() == NotificationType.CONSULT_REPLY)
                .count();
    }

    // ===== 스레드 생성 =====

    @Test
    @DisplayName("학부모 문의 스레드 생성 (내용 없이) → OPEN, 메시지 0건")
    void createInquiryWithoutContent() {
        ConsultationDto.CreateResponse resp = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "수업 문의", null));

        assertThat(resp.getConsultation().getType()).isEqualTo("INQUIRY");
        assertThat(resp.getConsultation().getStatus()).isEqualTo("OPEN");
        assertThat(resp.getConsultation().getTitle()).isEqualTo("수업 문의");
        assertThat(resp.getConsultation().getStudentName()).isEqualTo("홍학생");
        assertThat(resp.getConsultation().getMessageCount()).isZero();
        assertThat(resp.getFirstMessageId()).isNull();
    }

    @Test
    @DisplayName("학부모 문의 생성 시 content 포함 → 첫 메시지가 학부모 발신으로 저장")
    void createInquiryWithFirstMessage() {
        ConsultationDto.CreateResponse resp = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "수업 문의", "다음 달 수업료가 궁금합니다."));

        assertThat(resp.getFirstMessageId()).isNotNull();
        ConsultationDto.DetailResponse detail = resp.getConsultation();
        assertThat(detail.getMessageCount()).isEqualTo(1);
        assertThat(detail.getMessages().get(0).getSenderKind()).isEqualTo("PARENT");
        assertThat(detail.getMessages().get(0).getContent()).isEqualTo("다음 달 수업료가 궁금합니다.");
        assertThat(detail.getStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("아이소식(NEWS) 생성 → 학원 발신 첫 메시지 + CONSULT_REPLY 알림 발송")
    void createNews() {
        long before = consultReplyCount(ACADEMY);

        ConsultationDto.CreateResponse resp = consultationService.create(
                ACADEMY, ConsultationDto.CreateRequest.builder()
                        .studentId(studentId1).type("NEWS").title("10월 학습 리포트")
                        .content("이번 달 수학 진도가 80% 완료되었습니다.")
                        .authorMemberId(teacherId)
                        .build());

        assertThat(resp.getConsultation().getType()).isEqualTo("NEWS");
        assertThat(resp.getConsultation().getStatus()).isEqualTo("OPEN");
        assertThat(resp.getFirstMessageId()).isNotNull();
        assertThat(resp.getConsultation().getMessages().get(0).getSenderKind()).isEqualTo("STAFF");
        assertThat(resp.getConsultation().getMessages().get(0).getSenderName()).isEqualTo("김강사");
        // 알림 발송 확인
        assertThat(resp.getNotificationSentCount()).isEqualTo(1);
        assertThat(resp.getNotificationSkippedCount()).isZero();
        assertThat(consultReplyCount(ACADEMY)).isEqualTo(before + 1);
        List<ReaNotification> sent = notificationRepository.findByAcademyIdOrderByCreatedAtDesc(ACADEMY);
        ReaNotification latest = sent.get(0);
        assertThat(latest.getType()).isEqualTo(NotificationType.CONSULT_REPLY);
        assertThat(latest.getRecipientStudentId()).isEqualTo(studentId1);
        assertThat(latest.getTitle()).startsWith("[상담 답변]");
    }

    @Test
    @DisplayName("NEWS 생성 시 작성자 없으면 400, 내용 없으면 400")
    void createNewsValidation() {
        assertThatThrownBy(() -> consultationService.create(
                ACADEMY, ConsultationDto.CreateRequest.builder()
                        .studentId(studentId1).type("NEWS").title("제목")
                        .content("내용").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consultationService.create(
                ACADEMY, ConsultationDto.CreateRequest.builder()
                        .studentId(studentId1).type("NEWS").title("제목")
                        .authorMemberId(teacherId).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("authKey로 스레드 생성 가능 (학부모 인증키 로그인)")
    void createWithAuthKey() {
        ConsultationDto.CreateResponse resp = consultationService.create(
                ACADEMY, ConsultationDto.CreateRequest.builder()
                        .authKey(authKey1).type("INQUIRY").title("인증키 문의")
                        .content("인증키로 문의합니다.").build());

        assertThat(resp.getConsultation().getStudentId()).isEqualTo(studentId1);
    }

    @Test
    @DisplayName("제목 없으면 400, 타 학원 학생으로 생성하면 400")
    void createValidation() {
        assertThatThrownBy(() -> consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "  ", "내용")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consultationService.create(
                ACADEMY, inquiryRequest(otherStudentId, "제목", "내용")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 메시지·상태 전이 =====

    @Test
    @DisplayName("학부모 추가 메시지 → OPEN 유지, 알림 없음")
    void parentMessageKeepsOpen() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();
        long before = consultReplyCount(ACADEMY);

        ConsultationDto.CreateMessageResponse resp = consultationService.addMessage(
                ACADEMY, id, ConsultationDto.MessageRequest.builder()
                        .studentId(studentId1).content("추가 질문입니다.").build());

        assertThat(resp.getMessage().getSenderKind()).isEqualTo("PARENT");
        assertThat(consultationService.detail(ACADEMY, id, null, null).getStatus())
                .isEqualTo("OPEN");
        assertThat(consultReplyCount(ACADEMY)).isEqualTo(before); // 학부모 발신은 알림 없음
    }

    @Test
    @DisplayName("강사 답변 → ANSWERED 전환 + CONSULT_REPLY 알림 발송")
    void staffReplyMarksAnsweredAndNotifies() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();
        long before = consultReplyCount(ACADEMY);

        ConsultationDto.CreateMessageResponse resp = consultationService.addMessage(
                ACADEMY, id, ConsultationDto.MessageRequest.builder()
                        .memberId(teacherId).content("답변드립니다.").build());

        assertThat(resp.getMessage().getSenderKind()).isEqualTo("STAFF");
        assertThat(resp.getNotificationSentCount()).isEqualTo(1);
        assertThat(consultationService.detail(ACADEMY, id, null, null).getStatus())
                .isEqualTo("ANSWERED");
        assertThat(consultReplyCount(ACADEMY)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("ANSWERED 상태에서 학부모 추가 문의 → OPEN으로 복귀")
    void parentFollowUpReopens() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();
        consultationService.addMessage(ACADEMY, id, ConsultationDto.MessageRequest.builder()
                .memberId(teacherId).content("답변").build());
        assertThat(consultationService.detail(ACADEMY, id, null, null).getStatus())
                .isEqualTo("ANSWERED");

        consultationService.addMessage(ACADEMY, id, ConsultationDto.MessageRequest.builder()
                .studentId(studentId1).content("추가 문의").build());

        assertThat(consultationService.detail(ACADEMY, id, null, null).getStatus())
                .isEqualTo("OPEN");
    }

    @Test
    @DisplayName("타 학원 강사가 답변하면 400, 다른 학생 학부모가 작성하면 400")
    void messageSenderValidation() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();

        assertThatThrownBy(() -> consultationService.addMessage(
                ACADEMY, id, ConsultationDto.MessageRequest.builder()
                        .memberId(otherTeacherId).content("타학원 답변").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consultationService.addMessage(
                ACADEMY, id, ConsultationDto.MessageRequest.builder()
                        .studentId(studentId2).content("다른 학부모").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consultationService.addMessage(
                ACADEMY, id, ConsultationDto.MessageRequest.builder()
                        .content("발신자 없음").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("알림 OFF 설정 시 학원 답변 알림 skipped")
    void staffReplySkippedWhenOff() {
        notificationService.updateSettings(ACADEMY, NotificationDto.SettingsRequest.builder()
                .studentId(studentId1)
                .settings(List.of(NotificationDto.SettingUpdate.builder()
                        .type("CONSULT_REPLY").enabled(false).build()))
                .build());
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();
        long before = consultReplyCount(ACADEMY);

        ConsultationDto.CreateMessageResponse resp = consultationService.addMessage(
                ACADEMY, id, ConsultationDto.MessageRequest.builder()
                        .memberId(teacherId).content("답변").build());

        assertThat(resp.getNotificationSentCount()).isZero();
        assertThat(resp.getNotificationSkippedCount()).isEqualTo(1);
        assertThat(consultReplyCount(ACADEMY)).isEqualTo(before);
        assertThat(consultationService.detail(ACADEMY, id, null, null).getStatus())
                .isEqualTo("ANSWERED"); // 상태 전이는 정상 수행
    }

    // ===== 종료 =====

    @Test
    @DisplayName("스레드 종료 → CLOSED, 종료된 스레드 메시지는 409, 중복 종료는 409")
    void closeThread() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();

        ConsultationDto.DetailResponse closed = consultationService.close(
                ACADEMY, id, ConsultationDto.CloseRequest.builder()
                        .memberId(teacherId).build());
        assertThat(closed.getStatus()).isEqualTo("CLOSED");

        assertThatThrownBy(() -> consultationService.addMessage(
                ACADEMY, id, ConsultationDto.MessageRequest.builder()
                        .studentId(studentId1).content("종료 후 메시지").build()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> consultationService.close(
                ACADEMY, id, ConsultationDto.CloseRequest.builder()
                        .memberId(teacherId).build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("학부모가 본인 스레드 종료 가능, 타인 스레드 종료는 400")
    void closeByParent() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();

        ConsultationDto.DetailResponse closed = consultationService.close(
                ACADEMY, id, ConsultationDto.CloseRequest.builder()
                        .authKey(authKey1).build());
        assertThat(closed.getStatus()).isEqualTo("CLOSED");

        Long other = consultationService.create(
                ACADEMY, inquiryRequest(studentId2, "문의2", "내용")).getConsultation().getId();
        assertThatThrownBy(() -> consultationService.close(
                ACADEMY, other, ConsultationDto.CloseRequest.builder()
                        .authKey(authKey1).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 읽음 처리 =====

    @Test
    @DisplayName("읽음 처리: 학부모가 강사 메시지 읽음 → 멱등, 본인 메시지는 400")
    void markMessageRead() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();
        ConsultationDto.CreateMessageResponse reply = consultationService.addMessage(
                ACADEMY, id, ConsultationDto.MessageRequest.builder()
                        .memberId(teacherId).content("답변").build());
        Long staffMessageId = reply.getMessage().getId();
        Long parentMessageId = consultationService.detail(ACADEMY, id, null, null)
                .getMessages().get(0).getId();

        // 학부모가 강사 메시지 읽음
        ConsultationDto.ReadResponse read = consultationService.markMessageRead(
                ACADEMY, id, staffMessageId, ConsultationDto.ReadRequest.builder()
                        .studentId(studentId1).build());
        assertThat(read.isAlreadyRead()).isFalse();
        assertThat(read.getReadAt()).isNotNull();
        assertThat(read.getReaderName()).isEqualTo("홍부모");

        // 멱등
        ConsultationDto.ReadResponse reread = consultationService.markMessageRead(
                ACADEMY, id, staffMessageId, ConsultationDto.ReadRequest.builder()
                        .studentId(studentId1).build());
        assertThat(reread.isAlreadyRead()).isTrue();

        // 본인 메시지는 읽음 처리 불가
        assertThatThrownBy(() -> consultationService.markMessageRead(
                ACADEMY, id, parentMessageId, ConsultationDto.ReadRequest.builder()
                        .studentId(studentId1).build()))
                .isInstanceOf(IllegalArgumentException.class);

        // 다른 학생 학부모는 읽음 처리 불가
        assertThatThrownBy(() -> consultationService.markMessageRead(
                ACADEMY, id, staffMessageId, ConsultationDto.ReadRequest.builder()
                        .studentId(studentId2).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("강사가 학부모 메시지 읽음 처리 가능")
    void staffMarksParentMessageRead() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "첫 문의")).getConsultation().getId();
        Long parentMessageId = consultationService.detail(ACADEMY, id, null, null)
                .getMessages().get(0).getId();

        ConsultationDto.ReadResponse read = consultationService.markMessageRead(
                ACADEMY, id, parentMessageId, ConsultationDto.ReadRequest.builder()
                        .memberId(teacherId).build());
        assertThat(read.isAlreadyRead()).isFalse();
        assertThat(read.getReaderName()).isEqualTo("김강사");
    }

    // ===== 목록·상세·필터 =====

    @Test
    @DisplayName("타입·상태별 조회 필터")
    void listFilters() {
        Long inquiryOpen = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의A", "내용")).getConsultation().getId();
        consultationService.create(ACADEMY, ConsultationDto.CreateRequest.builder()
                .studentId(studentId1).type("NEWS").title("소식B")
                .content("소식 내용").authorMemberId(teacherId).build());
        consultationService.addMessage(ACADEMY, inquiryOpen,
                ConsultationDto.MessageRequest.builder()
                        .memberId(teacherId).content("답변").build());

        assertThat(consultationService.list(ACADEMY, "ANSWERED", null, null, null, null))
                .extracting(ConsultationDto.ListResponse::getId).containsExactly(inquiryOpen);
        assertThat(consultationService.list(ACADEMY, "OPEN", "NEWS", null, null, null))
                .hasSize(1)
                .allMatch(c -> c.getType().equals("NEWS"));
        assertThat(consultationService.list(ACADEMY, null, "INQUIRY", null, null, null))
                .extracting(ConsultationDto.ListResponse::getId).containsExactly(inquiryOpen);
        assertThat(consultationService.list(ACADEMY, "CLOSED", null, null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("학부모는 본인 스레드만 조회 (목록·상세)")
    void parentSeesOwnThreadsOnly() {
        Long mine = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "내 문의", "내용")).getConsultation().getId();
        consultationService.create(
                ACADEMY, inquiryRequest(studentId2, "남의 문의", "내용"));

        List<ConsultationDto.ListResponse> list =
                consultationService.list(ACADEMY, null, null, studentId1, null, null);
        assertThat(list).extracting(ConsultationDto.ListResponse::getId).containsExactly(mine);

        ConsultationDto.DetailResponse detail =
                consultationService.detail(ACADEMY, mine, studentId1, null);
        assertThat(detail.getUnreadCount()).isZero();
        assertThat(detail.getMessageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("학부모 상세: 미읽음 학원 메시지 건수 표시")
    void parentUnreadCount() {
        Long id = consultationService.create(
                ACADEMY, inquiryRequest(studentId1, "문의", "내용")).getConsultation().getId();
        consultationService.addMessage(ACADEMY, id, ConsultationDto.MessageRequest.builder()
                .memberId(teacherId).content("답변1").build());
        consultationService.addMessage(ACADEMY, id, ConsultationDto.MessageRequest.builder()
                .memberId(teacherId).content("답변2").build());

        ConsultationDto.DetailResponse detail =
                consultationService.detail(ACADEMY, id, studentId1, null);
        assertThat(detail.getUnreadCount()).isEqualTo(2);

        // 1건 읽음 처리 후 1건 남음
        Long first = detail.getMessages().get(1).getId();
        consultationService.markMessageRead(ACADEMY, id, first,
                ConsultationDto.ReadRequest.builder().studentId(studentId1).build());
        assertThat(consultationService.detail(ACADEMY, id, studentId1, null).getUnreadCount())
                .isEqualTo(1);
    }

    // ===== 학원 격리 =====

    @Test
    @DisplayName("타 학원 스레드·학생·강사 접근 시 400")
    void academyIsolation() {
        Long otherId = consultationService.create(
                OTHER_ACADEMY, inquiryRequest(otherStudentId, "타학원 문의", "내용"))
                .getConsultation().getId();

        assertThatThrownBy(() -> consultationService.detail(ACADEMY, otherId, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consultationService.addMessage(
                ACADEMY, otherId, ConsultationDto.MessageRequest.builder()
                        .memberId(teacherId).content("답변").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consultationService.close(
                ACADEMY, otherId, ConsultationDto.CloseRequest.builder()
                        .memberId(teacherId).build()))
                .isInstanceOf(IllegalArgumentException.class);
        // 목록은 학원별로 분리
        assertThat(consultationService.list(ACADEMY, null, null, null, null, null))
                .noneMatch(c -> c.getId().equals(otherId));
        assertThat(consultationService.list(OTHER_ACADEMY, null, null, null, null, null))
                .extracting(ConsultationDto.ListResponse::getId).containsExactly(otherId);
    }

    @Test
    @DisplayName("enum 대소문자 무시 파싱")
    void parseCaseInsensitive() {
        assertThat(ConsultationType.parse("inquiry")).isEqualTo(ConsultationType.INQUIRY);
        assertThat(ConsultationType.parse("news")).isEqualTo(ConsultationType.NEWS);
        assertThat(ConsultationStatus.parse("answered")).isEqualTo(ConsultationStatus.ANSWERED);
        assertThatThrownBy(() -> ConsultationType.parse("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsultationStatus.parse("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
