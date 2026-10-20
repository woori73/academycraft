package com.rea.academy.notice;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.notice.ReaNoticeRepository;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.security.AuthKeyFilter;
import com.rea.academy.service.MemberService;
import com.rea.academy.service.NoticeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 공지 소유권 동시 요청 검증 — 격리 MySQL 전용(rea.mysql.validation=true, 운영 DB 사용 금지).
 * 실제 커밋이 일어나므로 @Transactional 을 쓰지 않고 학원 ID 9100 번대를 전용으로 쓴다.
 */
@EnabledIfSystemProperty(named = "rea.mysql.validation", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysqlval")
class NoticeOwnershipMySqlTest {

    private static final int THREADS = 12;
    private static final String EDIT = "{\"title\":\"동시 수정\",\"content\":\"동시 수정\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberService memberService;
    @Autowired private NoticeService noticeService;
    @Autowired private ReaMemberRepository memberRepository;
    @Autowired private ReaNoticeRepository noticeRepository;

    private long member(Long academy, ReaRole role, String name, String phone) {
        return memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(academy).role(role).name(name).phone(phone).build()).getId();
    }

    private String key(long id) {
        return memberRepository.findById(id).orElseThrow().getAuthKey();
    }

    private long notice(Long academy, Long author) {
        return noticeService.create(academy, NoticeDto.CreateRequest.builder()
                .title("동시성").content("본문").authorId(author).build()).getNotice().getId();
    }

    private MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder r, String key) {
        return r.header(AuthKeyFilter.AUTH_KEY_HEADER, key);
    }

    private List<Integer> runConcurrently(List<Callable<Integer>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> job : jobs) {
            futures.add(pool.submit(() -> { start.await(); return job.call(); }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) statuses.add(f.get());
        pool.shutdown();
        return statuses;
    }

    @Test
    @DisplayName("다른 강사의 공지를 12개 요청이 동시에 수정·삭제해도 전부 403이고 공지는 그대로다")
    void givenOthersNotice_whenConcurrentTeacherRequests_thenAllForbidden() throws Exception {
        long academy = 9100L + (System.nanoTime() % 50);
        long t1 = member(academy, ReaRole.TEACHER, "동시강사1", "010-9100-0001");
        long t2 = member(academy, ReaRole.TEACHER, "동시강사2", "010-9100-0002");
        long id = notice(academy, t1);
        String k2 = key(t2);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean edit = i % 2 == 0;
            jobs.add(() -> mockMvc.perform(withKey(edit
                    ? put("/api/rea/{a}/notices/{id}", academy, id).contentType(MediaType.APPLICATION_JSON).content(EDIT)
                    : delete("/api/rea/{a}/notices/{id}", academy, id), k2)).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(jobs);
        assertThat(statuses).containsOnly(403);
        var n = noticeRepository.findById(id).orElseThrow();
        assertThat(n.getActive()).isTrue();
        assertThat(n.getTitle()).isEqualTo("동시성");
    }

    @Test
    @DisplayName("본인 공지를 12개 요청이 동시에 삭제하면 최소 1건 204, 나머지는 400이며 403·500은 없다")
    void givenOwnNotice_whenConcurrentDeletes_thenIdempotentWithoutServerErrors() throws Exception {
        long academy = 9150L + (System.nanoTime() % 50);
        long t1 = member(academy, ReaRole.TEACHER, "동시강사3", "010-9100-0003");
        long id = notice(academy, t1);
        String k1 = key(t1);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            jobs.add(() -> mockMvc.perform(withKey(delete("/api/rea/{a}/notices/{id}", academy, id), k1))
                    .andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(jobs);
        assertThat(statuses).contains(204).doesNotContain(403, 500);
        assertThat(statuses).allMatch(s -> s == 204 || s == 400);
        assertThat(noticeRepository.findById(id).orElseThrow().getActive()).isFalse();
    }

    @Test
    @DisplayName("원장 수정과 강사 본인 삭제가 동시에 와도 서버 오류 없이 한쪽 결과로 수렴한다")
    void givenOwnerEditAndTeacherDeleteRace_thenNoServerError() throws Exception {
        long academy = 9200L + (System.nanoTime() % 50);
        long owner = member(academy, ReaRole.ACADEMY_OWNER, "동시원장", "010-9100-0004");
        long t1 = member(academy, ReaRole.TEACHER, "동시강사4", "010-9100-0005");
        long id = notice(academy, t1);
        String ko = key(owner);
        String kt = key(t1);
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean ownerEdit = i % 2 == 0;
            jobs.add(() -> mockMvc.perform(withKey(ownerEdit
                    ? put("/api/rea/{a}/notices/{id}", academy, id).contentType(MediaType.APPLICATION_JSON).content(EDIT)
                    : delete("/api/rea/{a}/notices/{id}", academy, id), ownerEdit ? ko : kt))
                    .andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runConcurrently(jobs);
        assertThat(statuses).allMatch(s -> s == 200 || s == 204 || s == 400);
        assertThat(noticeRepository.findById(id).orElseThrow().getAuthorId()).isEqualTo(t1);
    }
}
