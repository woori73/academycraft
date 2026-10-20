package com.rea.academy.notice;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.notice.ReaNoticeRepository;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.security.AuthKeyFilter;
import com.rea.academy.service.MemberService;
import com.rea.academy.service.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공지 수정·삭제 소유권 정책(원장=전체, 강사=본인만, 작성자 NULL=원장만, 타 학원 격리, 작성자 위조 방지). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NoticeOwnershipTest {

    private static final Long ACADEMY_A = 1L;
    private static final Long ACADEMY_B = 2L;
    private static final String EDIT = "{\"title\":\"수정된 제목\",\"content\":\"수정된 내용\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberService memberService;
    @Autowired private NoticeService noticeService;
    @Autowired private ReaMemberRepository memberRepository;
    @Autowired private ReaNoticeRepository noticeRepository;

    private long ownerA;
    private long teacherA1;
    private long teacherA2;
    private String ownerKeyA;
    private String teacherKeyA1;
    private String teacherKeyA2;
    private String ownerKeyB;
    private String teacherKeyB;

    private long member(Long academy, ReaRole role, String name, String phone) {
        return memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(academy).role(role).name(name).phone(phone).build()).getId();
    }

    private String key(long memberId) {
        return memberRepository.findById(memberId).orElseThrow().getAuthKey();
    }

    private long noticeBy(Long academy, Long authorId) {
        return noticeService.create(academy, NoticeDto.CreateRequest.builder()
                .title("공지").content("본문").authorId(authorId).build()).getNotice().getId();
    }

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder req, String key) {
        if (key != null) req.header(AuthKeyFilter.AUTH_KEY_HEADER, key);
        return req;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @BeforeEach
    void setUp() {
        ownerA = member(ACADEMY_A, ReaRole.ACADEMY_OWNER, "원장A", "010-1000-0001");
        teacherA1 = member(ACADEMY_A, ReaRole.TEACHER, "강사A1", "010-1000-0002");
        teacherA2 = member(ACADEMY_A, ReaRole.TEACHER, "강사A2", "010-1000-0003");
        long ownerB = member(ACADEMY_B, ReaRole.ACADEMY_OWNER, "원장B", "010-2000-0001");
        long teacherB = member(ACADEMY_B, ReaRole.TEACHER, "강사B", "010-2000-0002");
        ownerKeyA = key(ownerA);
        teacherKeyA1 = key(teacherA1);
        teacherKeyA2 = key(teacherA2);
        ownerKeyB = key(ownerB);
        teacherKeyB = key(teacherB);
    }

    @Test
    @DisplayName("원장은 강사가 쓴 공지를 수정·삭제할 수 있다")
    void givenTeacherNotice_whenOwnerEditsAndDeletes_thenAllowed() throws Exception {
        long id = noticeBy(ACADEMY_A, teacherA1);
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), EDIT), ownerKeyA))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", ACADEMY_A, id), ownerKeyA))
                .andExpect(status().isNoContent());
        assertThat(noticeRepository.findById(id).orElseThrow().getActive()).isFalse();
    }

    @Test
    @DisplayName("강사는 본인 공지를 수정·삭제할 수 있다")
    void givenOwnNotice_whenTeacherEditsAndDeletes_thenAllowed() throws Exception {
        long id = noticeBy(ACADEMY_A, teacherA1);
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), EDIT), teacherKeyA1))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", ACADEMY_A, id), teacherKeyA1))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("강사는 원장·다른 강사의 공지를 수정·삭제할 수 없다(403)이고 공지는 그대로다")
    void givenOthersNotice_whenTeacherEditsAndDeletes_thenForbiddenAndUnchanged() throws Exception {
        long ownersNotice = noticeBy(ACADEMY_A, ownerA);
        long otherTeachers = noticeBy(ACADEMY_A, teacherA2);
        for (long id : new long[] {ownersNotice, otherTeachers}) {
            mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), EDIT), teacherKeyA1))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", ACADEMY_A, id), teacherKeyA1))
                    .andExpect(status().isForbidden());
            var n = noticeRepository.findById(id).orElseThrow();
            assertThat(n.getActive()).isTrue();
            assertThat(n.getTitle()).isEqualTo("공지");
        }
    }

    @Test
    @DisplayName("작성자(author_id)가 NULL인 공지는 강사 403, 원장만 수정·삭제")
    void givenNullAuthorNotice_whenTeacherThenOwner_thenOnlyOwnerAllowed() throws Exception {
        long id = noticeBy(ACADEMY_A, null);
        assertThat(noticeRepository.findById(id).orElseThrow().getAuthorId()).isNull();
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), EDIT), teacherKeyA1))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", ACADEMY_A, id), teacherKeyA1))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), EDIT), ownerKeyA))
                .andExpect(status().isOk());
        mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", ACADEMY_A, id), ownerKeyA))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("타 학원: 다른 학원 경로는 403, 내 경로에서 다른 학원 공지 id는 원장·강사 모두 변경 불가")
    void givenOtherAcademyNotice_whenAccessed_thenIsolated() throws Exception {
        long inB = noticeBy(ACADEMY_B, null);
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_B, inB), EDIT), ownerKeyA))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", ACADEMY_B, inB), teacherKeyA1))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, inB), EDIT), ownerKeyA))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", ACADEMY_A, inB), teacherKeyA1))
                .andExpect(status().isBadRequest());
        var n = noticeRepository.findById(inB).orElseThrow();
        assertThat(n.getActive()).isTrue();
        assertThat(n.getTitle()).isEqualTo("공지");
    }

    @Test
    @DisplayName("작성자 위조: 강사가 body로 원장 ID를 작성자로 보내도 서버가 로그인 사용자로 덮어쓴다")
    void givenForgedAuthorId_whenTeacherCreates_thenAuthorIsLoggedInUser() throws Exception {
        String body = "{\"title\":\"위조 시도\",\"content\":\"본문\",\"authorId\":" + ownerA + "}";
        mockMvc.perform(withKey(json(post("/api/rea/{a}/notices", ACADEMY_A), body), teacherKeyA1))
                .andExpect(status().isOk());
        var created = noticeRepository.findAll().stream()
                .filter(n -> "위조 시도".equals(n.getTitle())).findFirst().orElseThrow();
        assertThat(created.getAuthorId()).isEqualTo(teacherA1);
        // 위조가 통했다면 다른 강사가 원장 소유로 수정 가능했을 것 → 강사2는 여전히 403
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, created.getId()), EDIT), teacherKeyA2))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("수정 body에 authorId·작성자 필드를 넣어도 작성자는 바뀌지 않는다")
    void givenAuthorFieldInUpdateBody_whenOwnerEdits_thenAuthorUnchanged() throws Exception {
        long id = noticeBy(ACADEMY_A, teacherA1);
        String body = "{\"title\":\"수정\",\"authorId\":" + teacherA2 + ",\"authorName\":\"가짜\"}";
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), body), ownerKeyA))
                .andExpect(status().isOk());
        assertThat(noticeRepository.findById(id).orElseThrow().getAuthorId()).isEqualTo(teacherA1);
    }

    @Test
    @DisplayName("인증 없음은 작성·수정·삭제 모두 401")
    void givenNoAuthKey_whenModifying_thenUnauthorized() throws Exception {
        long id = noticeBy(ACADEMY_A, teacherA1);
        mockMvc.perform(json(post("/api/rea/{a}/notices", ACADEMY_A), "{\"title\":\"x\",\"content\":\"y\",\"authorId\":" + ownerA + "}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), EDIT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/rea/{a}/notices/{id}", ACADEMY_A, id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("이미 삭제된 공지는 원장도 수정·삭제할 수 없고(400) 강사 정책과 무관하게 존재를 숨긴다")
    void givenDeactivatedNotice_whenOwnerEdits_thenBadRequest() throws Exception {
        long id = noticeBy(ACADEMY_A, teacherA1);
        noticeService.delete(ACADEMY_A, id);
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), EDIT), ownerKeyA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("타 학원 강사 키로는 우리 학원 공지를 건드릴 수 없다")
    void givenTeacherOfOtherAcademy_whenEditsOurNotice_thenForbidden() throws Exception {
        long id = noticeBy(ACADEMY_A, null);
        mockMvc.perform(withKey(json(put("/api/rea/{a}/notices/{id}", ACADEMY_A, id), EDIT), teacherKeyB))
                .andExpect(status().isForbidden());
        mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", ACADEMY_A, id), ownerKeyB))
                .andExpect(status().isForbidden());
    }
}
