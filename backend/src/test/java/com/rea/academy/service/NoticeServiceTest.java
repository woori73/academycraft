package com.rea.academy.service;

import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.notification.NotificationType;
import com.rea.academy.domain.notification.ReaNotification;
import com.rea.academy.domain.notification.ReaNotificationRepository;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * 공지 관리 서비스 테스트 (H2 인메모리).
 * - 작성 → 목록 → 상세 → 수정 → 삭제
 * - 전체공지 / 반소식 대상
 * - 학부모 통합 조회 (전체공지 + 자녀 소속 반)
 * - 조회수: 관리자만 표시
 * - 읽음 확인 기록 (멱등) · 확인자 목록
 * - 공지 등록 시 NOTICE 알림 발송 (Mock)
 * - 학원 격리
 */
@SpringBootTest
@Transactional
class NoticeServiceTest {

    @Autowired
    private NoticeService noticeService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ClassService classService;

    @Autowired
    private ReaNotificationRepository notificationRepository;

    private static final Long ACADEMY = 1L;
    private static final Long OTHER_ACADEMY = 2L;

    private Long teacherId;
    private Long classIdA;
    private Long classIdB;
    private Long studentId1; // A반
    private Long studentId2; // A반
    private Long studentId3; // B반
    private String authKey1;
    private String authKey3;

    @BeforeEach
    void setUp() {
        teacherId = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(ACADEMY).role(ReaRole.TEACHER)
                        .name("김강사").phone("010-1234-5678")
                        .build()).getId();

        StudentDto.DetailResponse s1 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("홍학생").grade("고2")
                        .parentName("홍부모").parentPhone("010-1111-2222")
                        .build());
        StudentDto.DetailResponse s2 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("박학생").grade("고1")
                        .build());
        StudentDto.DetailResponse s3 = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("최학생").grade("중3")
                        .build());
        studentId1 = s1.getId();
        studentId2 = s2.getId();
        studentId3 = s3.getId();
        authKey1 = s1.getAuthKey();
        authKey3 = s3.getAuthKey();

        classIdA = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name("수학 A반").teacherId(teacherId).build()).getId();
        classIdB = classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name("영어 B반").teacherId(teacherId).build()).getId();

        classService.assignStudents(ACADEMY, classIdA, List.of(studentId1, studentId2));
        classService.assignStudents(ACADEMY, classIdB, List.of(studentId3));
    }

    private NoticeDto.CreateRequest baseCreateRequest() {
        return NoticeDto.CreateRequest.builder()
                .title("방학 특강 안내")
                .content("7월 28일부터 방학 특강을 시작합니다.")
                .authorId(teacherId)
                .build();
    }

    private long noticeNotificationCount(Long academyId) {
        return notificationRepository.findByAcademyIdOrderByCreatedAtDesc(academyId).stream()
                .filter(n -> n.getType() == NotificationType.NOTICE)
                .count();
    }

    private List<Long> noticeRecipientIds(Long academyId) {
        return notificationRepository.findByAcademyIdOrderByCreatedAtDesc(academyId).stream()
                .filter(n -> n.getType() == NotificationType.NOTICE)
                .map(ReaNotification::getRecipientStudentId)
                .collect(Collectors.toList());
    }

    // ===== 작성 =====

    @Test
    @DisplayName("전체공지 작성 성공 + 학원 전체에 NOTICE 알림 발송")
    void create_allNotice_sendsToAll() {
        NoticeDto.CreateResponse res = noticeService.create(ACADEMY, baseCreateRequest());

        assertThat(res.getNotice().getId()).isNotNull();
        assertThat(res.getNotice().getScope()).isEqualTo(NoticeDto.SCOPE_ALL);
        assertThat(res.getNotice().getClassId()).isNull();
        assertThat(res.getNotice().getAuthorName()).isEqualTo("김강사");
        assertThat(res.getNotificationSentCount()).isEqualTo(3);
        assertThat(res.getNotificationError()).isNull();

        // 알림 이력: 3명 전원에게 NOTICE 알림 기록
        assertThat(noticeNotificationCount(ACADEMY)).isEqualTo(3);
        assertThat(noticeRecipientIds(ACADEMY))
                .containsExactlyInAnyOrder(studentId1, studentId2, studentId3);
    }

    @Test
    @DisplayName("반소식 작성 성공 + 해당 반 학부모에게만 NOTICE 알림 발송")
    void create_classNotice_sendsToClassOnly() {
        NoticeDto.CreateRequest req = baseCreateRequest();
        req.setClassId(classIdA);
        NoticeDto.CreateResponse res = noticeService.create(ACADEMY, req);

        assertThat(res.getNotice().getScope()).isEqualTo(NoticeDto.SCOPE_CLASS);
        assertThat(res.getNotice().getClassId()).isEqualTo(classIdA);
        assertThat(res.getNotificationSentCount()).isEqualTo(2);

        // A반 소속 2명에게만 발송
        assertThat(noticeRecipientIds(ACADEMY))
                .containsExactlyInAnyOrder(studentId1, studentId2);
    }

    @Test
    @DisplayName("작성 검증: 제목/내용 필수, 타 학원 반·작성자 거부")
    void create_validation() {
        NoticeDto.CreateRequest noTitle = baseCreateRequest();
        noTitle.setTitle(" ");
        assertThatThrownBy(() -> noticeService.create(ACADEMY, noTitle))
                .isInstanceOf(IllegalArgumentException.class);

        NoticeDto.CreateRequest noContent = baseCreateRequest();
        noContent.setContent(null);
        assertThatThrownBy(() -> noticeService.create(ACADEMY, noContent))
                .isInstanceOf(IllegalArgumentException.class);

        // 타 학원의 반을 대상으로 지정
        Long otherTeacher = memberService.createMember(
                MemberDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).role(ReaRole.TEACHER)
                        .name("타학원강사").build()).getId();
        Long otherClass = classService.createClass(OTHER_ACADEMY,
                ClassDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원반").teacherId(otherTeacher)
                        .build()).getId();
        NoticeDto.CreateRequest otherClassReq = baseCreateRequest();
        otherClassReq.setClassId(otherClass);
        assertThatThrownBy(() -> noticeService.create(ACADEMY, otherClassReq))
                .isInstanceOf(IllegalArgumentException.class);

        // 타 학원 회원을 작성자로 지정
        NoticeDto.CreateRequest otherAuthorReq = baseCreateRequest();
        otherAuthorReq.setAuthorId(otherTeacher);
        assertThatThrownBy(() -> noticeService.create(ACADEMY, otherAuthorReq))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("작성: 첨부파일 메타데이터 저장")
    void create_withAttachment() {
        NoticeDto.CreateRequest req = baseCreateRequest();
        req.setAttachmentFileName("특강-안내.pdf");
        req.setAttachmentFileKey("notices/1/notice-1.pdf");
        NoticeDto.DetailResponse res = noticeService.create(ACADEMY, req).getNotice();

        assertThat(res.getAttachmentFileName()).isEqualTo("특강-안내.pdf");
        assertThat(res.getAttachmentFileKey()).isEqualTo("notices/1/notice-1.pdf");
    }

    // ===== 목록 =====

    @Test
    @DisplayName("관리자 목록: 전체공지+반소식 전부, scope·classId 필터")
    void list_admin() {
        Long allId = noticeService.create(ACADEMY, baseCreateRequest()).getNotice().getId();
        NoticeDto.CreateRequest classReq = baseCreateRequest();
        classReq.setClassId(classIdA);
        classReq.setTitle("A반 소식");
        Long classNoticeId = noticeService.create(ACADEMY, classReq).getNotice().getId();

        List<NoticeDto.ListResponse> all = noticeService.list(ACADEMY, null, null, null, null);
        assertThat(all).extracting(NoticeDto.ListResponse::getId)
                .containsExactlyInAnyOrder(allId, classNoticeId);

        // scope 필터
        assertThat(noticeService.list(ACADEMY, "ALL", null, null, null))
                .extracting(NoticeDto.ListResponse::getId).containsExactly(allId);
        assertThat(noticeService.list(ACADEMY, "CLASS", null, null, null))
                .extracting(NoticeDto.ListResponse::getId).containsExactly(classNoticeId);

        // classId 필터
        assertThat(noticeService.list(ACADEMY, null, classIdA, null, null))
                .extracting(NoticeDto.ListResponse::getId).containsExactly(classNoticeId);

        // 잘못된 scope
        assertThatThrownBy(() -> noticeService.list(ACADEMY, "WRONG", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // 타 학원 반 필터
        assertThatThrownBy(() -> noticeService.list(ACADEMY, null, 99999L, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // 관리자 목록에는 조회수 포함
        assertThat(all).allMatch(r -> r.getViewCount() != null);
    }

    @Test
    @DisplayName("학부모 목록: 전체공지 + 자녀 소속 반 공지만 통합 조회")
    void list_parent() {
        noticeService.create(ACADEMY, baseCreateRequest()); // 전체공지
        NoticeDto.CreateRequest reqA = baseCreateRequest();
        reqA.setClassId(classIdA);
        reqA.setTitle("A반 소식");
        noticeService.create(ACADEMY, reqA);
        NoticeDto.CreateRequest reqB = baseCreateRequest();
        reqB.setClassId(classIdB);
        reqB.setTitle("B반 소식");
        noticeService.create(ACADEMY, reqB);

        // 홍학생(A반 학부모): 전체공지 + A반 소식만
        List<NoticeDto.ListResponse> parent1 =
                noticeService.list(ACADEMY, null, null, studentId1, null);
        assertThat(parent1).extracting(NoticeDto.ListResponse::getTitle)
                .containsExactlyInAnyOrder("방학 특강 안내", "A반 소식");

        // authKey 조회도 동일
        List<NoticeDto.ListResponse> parent1ByKey =
                noticeService.list(ACADEMY, null, null, null, authKey1);
        assertThat(parent1ByKey).hasSize(2);

        // scope=CLASS: 반소식만
        List<NoticeDto.ListResponse> classOnly =
                noticeService.list(ACADEMY, "CLASS", null, studentId1, null);
        assertThat(classOnly).extracting(NoticeDto.ListResponse::getTitle)
                .containsExactly("A반 소식");

        // scope=ALL: 전체공지
        List<NoticeDto.ListResponse> allOnly =
                noticeService.list(ACADEMY, "all", null, studentId1, null);
        assertThat(allOnly).extracting(NoticeDto.ListResponse::getTitle)
                .containsExactly("방학 특강 안내");

        // 최학생(B반 학부모): 전체공지 + B반 소식만
        List<NoticeDto.ListResponse> parent3 =
                noticeService.list(ACADEMY, null, null, studentId3, null);
        assertThat(parent3).extracting(NoticeDto.ListResponse::getTitle)
                .containsExactlyInAnyOrder("방학 특강 안내", "B반 소식");

        // 학부모 목록에는 조회수 미표시
        assertThat(parent1).allMatch(r -> r.getViewCount() == null);

        // 타 반 classId로 조회 시도 → 400
        assertThatThrownBy(() -> noticeService.list(ACADEMY, null, classIdB, studentId1, null))
                .isInstanceOf(IllegalArgumentException.class);

        // 타 학원 학생으로 조회 → 400
        Long otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원생").build()).getId();
        assertThatThrownBy(() -> noticeService.list(ACADEMY, null, null, otherStudent, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 상세 =====

    @Test
    @DisplayName("상세: 조회수 +1, 관리자만 조회수 표시")
    void detail_viewCount() {
        Long noticeId = noticeService.create(ACADEMY, baseCreateRequest()).getNotice().getId();

        // 관리자 조회 1회차: viewCount=1 표시
        NoticeDto.DetailResponse d1 = noticeService.detail(ACADEMY, noticeId, null, null);
        assertThat(d1.getViewCount()).isEqualTo(1);
        assertThat(d1.getContent()).isEqualTo("7월 28일부터 방학 특강을 시작합니다.");
        assertThat(d1.getMyRead()).isNull();

        // 관리자 조회 2회차: viewCount=2
        NoticeDto.DetailResponse d2 = noticeService.detail(ACADEMY, noticeId, null, null);
        assertThat(d2.getViewCount()).isEqualTo(2);

        // 학부모 조회: viewCount null, myRead=false, 조회수는 증가(3)
        NoticeDto.DetailResponse d3 = noticeService.detail(ACADEMY, noticeId, studentId1, null);
        assertThat(d3.getViewCount()).isNull();
        assertThat(d3.getMyRead()).isFalse();

        // 관리자 재조회: 4
        NoticeDto.DetailResponse d4 = noticeService.detail(ACADEMY, noticeId, null, null);
        assertThat(d4.getViewCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("상세: 학부모는 타 반 공지 조회 불가, 타 학원 공지 400")
    void detail_parentIsolation() {
        NoticeDto.CreateRequest req = baseCreateRequest();
        req.setClassId(classIdB);
        Long noticeId = noticeService.create(ACADEMY, req).getNotice().getId();

        // A반 학부모가 B반 공지 상세 조회 → 400
        assertThatThrownBy(() -> noticeService.detail(ACADEMY, noticeId, studentId1, null))
                .isInstanceOf(IllegalArgumentException.class);

        // authKey로도 동일
        assertThatThrownBy(() -> noticeService.detail(ACADEMY, noticeId, null, authKey1))
                .isInstanceOf(IllegalArgumentException.class);

        // 전체공지는 학부모도 조회 가능
        Long allId = noticeService.create(ACADEMY, baseCreateRequest()).getNotice().getId();
        NoticeDto.DetailResponse d = noticeService.detail(ACADEMY, allId, studentId1, null);
        assertThat(d.getId()).isEqualTo(allId);

        // 타 학원 공지 조회 → 400
        assertThatThrownBy(() -> noticeService.detail(OTHER_ACADEMY, noticeId, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 수정·삭제 =====

    @Test
    @DisplayName("수정: 제목·대상 변경, 전체공지 전환")
    void update() {
        NoticeDto.CreateRequest req = baseCreateRequest();
        req.setClassId(classIdA);
        Long noticeId = noticeService.create(ACADEMY, req).getNotice().getId();

        NoticeDto.DetailResponse updated = noticeService.update(ACADEMY, noticeId,
                NoticeDto.UpdateRequest.builder()
                        .title("방학 특강 변경 안내")
                        .content("일정이 변경되었습니다.")
                        .build());
        assertThat(updated.getTitle()).isEqualTo("방학 특강 변경 안내");
        assertThat(updated.getContent()).isEqualTo("일정이 변경되었습니다.");
        assertThat(updated.getScope()).isEqualTo(NoticeDto.SCOPE_CLASS);

        // 반소식 → 전체공지 전환
        NoticeDto.DetailResponse toAll = noticeService.update(ACADEMY, noticeId,
                NoticeDto.UpdateRequest.builder().changeToAll(true).build());
        assertThat(toAll.getScope()).isEqualTo(NoticeDto.SCOPE_ALL);
        assertThat(toAll.getClassId()).isNull();

        // 전체공지 → 반소식 전환
        NoticeDto.DetailResponse toClass = noticeService.update(ACADEMY, noticeId,
                NoticeDto.UpdateRequest.builder().classId(classIdB).build());
        assertThat(toClass.getScope()).isEqualTo(NoticeDto.SCOPE_CLASS);
        assertThat(toClass.getClassId()).isEqualTo(classIdB);
        assertThat(toClass.getClassName()).isEqualTo("영어 B반");

        // 빈 제목은 거부
        assertThatThrownBy(() -> noticeService.update(ACADEMY, noticeId,
                NoticeDto.UpdateRequest.builder().title("  ").build()))
                .isInstanceOf(IllegalArgumentException.class);

        // 타 학원 반 지정 거부
        assertThatThrownBy(() -> noticeService.update(ACADEMY, noticeId,
                NoticeDto.UpdateRequest.builder().classId(99999L).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("삭제: 비활성화 후 목록·상세에서 제외")
    void delete() {
        Long noticeId = noticeService.create(ACADEMY, baseCreateRequest()).getNotice().getId();

        noticeService.delete(ACADEMY, noticeId);

        assertThat(noticeService.list(ACADEMY, null, null, null, null)).isEmpty();
        assertThatThrownBy(() -> noticeService.detail(ACADEMY, noticeId, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // 삭제된 공지 수정도 불가
        assertThatThrownBy(() -> noticeService.update(ACADEMY, noticeId,
                NoticeDto.UpdateRequest.builder().title("x").build()))
                .isInstanceOf(IllegalArgumentException.class);
        // 타 학원 삭제 시도 → 400
        Long other = noticeService.create(ACADEMY, baseCreateRequest()).getNotice().getId();
        assertThatThrownBy(() -> noticeService.delete(OTHER_ACADEMY, other))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 읽음 확인 =====

    @Test
    @DisplayName("읽음 확인: 기록 저장·멱등·확인자 목록")
    void markRead_idempotent() {
        Long noticeId = noticeService.create(ACADEMY, baseCreateRequest()).getNotice().getId();

        // 첫 읽음 확인
        NoticeDto.ReadResponse r1 = noticeService.markRead(ACADEMY, noticeId,
                NoticeDto.ReadRequest.builder().studentId(studentId1).build());
        assertThat(r1.isAlreadyRead()).isFalse();
        assertThat(r1.getReadAt()).isNotNull();

        // 중복 읽음 확인 → 멱등 (같은 기록)
        NoticeDto.ReadResponse r2 = noticeService.markRead(ACADEMY, noticeId,
                NoticeDto.ReadRequest.builder().studentId(studentId1).build());
        assertThat(r2.isAlreadyRead()).isTrue();
        assertThat(r2.getReadAt()).isEqualTo(r1.getReadAt());

        // authKey로도 가능
        NoticeDto.ReadResponse r3 = noticeService.markRead(ACADEMY, noticeId,
                NoticeDto.ReadRequest.builder().authKey(authKey3).build());
        assertThat(r3.isAlreadyRead()).isFalse();
        assertThat(r3.getStudentName()).isEqualTo("최학생");

        // 확인자 목록 (관리자)
        List<NoticeDto.ReaderResponse> readers = noticeService.readers(ACADEMY, noticeId);
        assertThat(readers).extracting(NoticeDto.ReaderResponse::getStudentName)
                .containsExactlyInAnyOrder("홍학생", "최학생");

        // 상세 조회 myRead=true
        NoticeDto.DetailResponse d = noticeService.detail(ACADEMY, noticeId, studentId1, null);
        assertThat(d.getMyRead()).isTrue();
        assertThat(d.getReadCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("읽음 확인: 타 반 공지·타 학원 학생은 거부")
    void markRead_isolation() {
        NoticeDto.CreateRequest req = baseCreateRequest();
        req.setClassId(classIdB);
        Long noticeId = noticeService.create(ACADEMY, req).getNotice().getId();

        // A반 학부모가 B반 공지 읽음 확인 → 400
        assertThatThrownBy(() -> noticeService.markRead(ACADEMY, noticeId,
                NoticeDto.ReadRequest.builder().studentId(studentId1).build()))
                .isInstanceOf(IllegalArgumentException.class);

        // 타 학원 학생 → 400
        Long otherStudent = memberService.createStudent(
                StudentDto.CreateRequest.builder()
                        .academyId(OTHER_ACADEMY).name("타학원생2").build()).getId();
        Long allId = noticeService.create(ACADEMY, baseCreateRequest()).getNotice().getId();
        assertThatThrownBy(() -> noticeService.markRead(ACADEMY, allId,
                NoticeDto.ReadRequest.builder().studentId(otherStudent).build()))
                .isInstanceOf(IllegalArgumentException.class);

        // 식별자 없이 → 400
        assertThatThrownBy(() -> noticeService.markRead(ACADEMY, allId,
                NoticeDto.ReadRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 학원 격리 =====

    @Test
    @DisplayName("학원 격리: 타 학원 공지 접근 불가")
    void academyIsolation() {
        Long noticeId = noticeService.create(ACADEMY, baseCreateRequest()).getNotice().getId();

        // 타 학원에서는 목록에 보이지 않음
        assertThat(noticeService.list(OTHER_ACADEMY, null, null, null, null)).isEmpty();

        // 타 학원에서 상세·수정·삭제·확인자 목록 → 400
        assertThatThrownBy(() -> noticeService.detail(OTHER_ACADEMY, noticeId, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> noticeService.update(OTHER_ACADEMY, noticeId,
                NoticeDto.UpdateRequest.builder().title("해킹").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> noticeService.delete(OTHER_ACADEMY, noticeId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> noticeService.readers(OTHER_ACADEMY, noticeId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 1~3단계 회귀 =====

    @Test
    @DisplayName("회귀: 1단계 학생 등록·2단계 출결·3단계 알림 연동 정상")
    void regression_earlierPhases() {
        // 1단계: 학생 인증키 발급
        assertThat(authKey1).isNotBlank();

        // 3단계: 공지 알림은 NotificationType.NOTICE로 기록됨
        NoticeDto.CreateResponse res = noticeService.create(ACADEMY, baseCreateRequest());
        assertThat(res.getNotificationSentCount()).isEqualTo(3);
        List<ReaNotification> sent = notificationRepository
                .findByAcademyIdOrderByCreatedAtDesc(ACADEMY).stream()
                .filter(n -> n.getType() == NotificationType.NOTICE)
                .collect(Collectors.toList());
        assertThat(sent).hasSize(3);
        assertThat(sent).allMatch(n -> n.getTitle().startsWith("[공지] "));
    }
}
