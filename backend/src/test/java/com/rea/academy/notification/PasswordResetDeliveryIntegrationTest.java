package com.rea.academy.notification;

import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.notification.delivery.PasswordResetDeliveryService;
import com.rea.academy.domain.notification.delivery.PasswordResetRateLimiter;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.service.AuthService;
import com.rea.academy.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비밀번호 재설정 발송 통합 테스트 (MockMvc + Mock 발송 서비스).
 *
 * <p>실제 외부 발송 없음. 검증:
 * 발송 호출·이메일 전달·계정 존재 여부 비노출·요청 제한·
 * 트랜잭션 커밋 후 발송·롤백 시 미발송.</p>
 *
 * <p>참고: @Transactional을 사용하지 않는다.
 * 발송이 afterCommit에 예약되므로 테스트 트랜잭션(기본 롤백)에서는
 * 발송이 실행되지 않기 때문이다. 정리는 @AfterEach에서 직접 수행.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetDeliveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberService memberService;

    @Autowired
    private AuthService authService;

    @Autowired
    private ReaMemberRepository memberRepository;

    @Autowired
    private PasswordResetRateLimiter rateLimiter;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockBean
    private PasswordResetDeliveryService deliveryService;

    private Long memberId;

    private String requestBody(String loginId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/rea/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        memberId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(1L).name("발송테스트").phone("010-1234-5678")
                .role(ReaRole.TEACHER).build()).getId();
        ReaMember member = memberRepository.findById(memberId).orElseThrow();
        member.changeEmail("test@example.com");
        memberRepository.save(member);
        authService.setupCredentials(member.getAuthKey(), "deliveryUser", "Pass1234!");
    }

    @AfterEach
    void tearDown() {
        rateLimiter.reset();
        if (memberId != null) {
            memberRepository.deleteById(memberId);
        }
    }

    @Test
    @DisplayName("존재하는 계정 → 커밋 후 발송 서비스 호출 (전화번호·이메일 전달)")
    void existingAccount_deliversAfterCommit() throws Exception {
        String body = requestBody("deliveryUser");
        assertThat(body).contains("재설정 안내를 발송했습니다");
        // HTTP 요청의 트랜잭션이 커밋되었으므로 afterCommit 실행됨
        verify(deliveryService, times(1))
                .deliverAsync(eq("010-1234-5678"), eq("test@example.com"), anyString(), anyLong());
    }

    @Test
    @DisplayName("이메일이 null이 아닌 값으로 전달됨 (SMS→이메일 폴백 가능)")
    void emailNotNull_passedToDelivery() throws Exception {
        requestBody("deliveryUser");
        verify(deliveryService).deliverAsync(
                anyString(), argThat(email -> email != null && email.contains("@")),
                anyString(), anyLong());
    }

    @Test
    @DisplayName("존재하지 않는 계정 → 발송 없음, 응답은 동일 (누설 방지)")
    void unknownAccount_sameResponse_noDelivery() throws Exception {
        String bodyKnown = requestBody("deliveryUser");
        String bodyUnknown = requestBody("nobody123");
        assertThat(bodyUnknown).contains("재설정 안내를 발송했습니다");
        assertThat(bodyKnown).contains("재설정 안내를 발송했습니다");
        verify(deliveryService, times(1)).deliverAsync(anyString(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("발송 실패해도 200 반환, 토큰은 유효하게 유지 (재요청 가능)")
    void deliveryFailure_tokenStillValid() throws Exception {
        String body = requestBody("deliveryUser");
        assertThat(body).contains("재설정 안내를 발송했습니다");

        int s = body.indexOf("\"resetToken\":\"");
        assertThat(s).isGreaterThanOrEqualTo(0);
        String token = body.substring(s + 14, body.indexOf("\"", s + 14));
        mockMvc.perform(post("/api/rea/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"NewPass5678\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("시간당 5회 초과 → 6회째는 토큰 미발급, 응답은 동일")
    void rateLimitExceeded_sameResponse() throws Exception {
        for (int i = 0; i < 5; i++) {
            requestBody("deliveryUser");
        }
        String body6 = requestBody("deliveryUser");
        assertThat(body6).contains("재설정 안내를 발송했습니다");
        verify(deliveryService, times(5)).deliverAsync(anyString(), any(), anyString(), anyLong());
        assertThat(body6).doesNotContain("\"resetToken\":\"");
    }

    @Test
    @DisplayName("트랜잭션 커밋 시 발송됨")
    void commit_triggersDelivery() {
        new TransactionTemplate(txManager).execute(status -> {
            authService.requestPasswordReset("deliveryUser");
            return null;
        });
        verify(deliveryService, times(1))
                .deliverAsync(eq("010-1234-5678"), eq("test@example.com"), anyString(), eq(memberId));
    }

    @Test
    @DisplayName("트랜잭션 롤백 시 발송되지 않음")
    void rollback_noDelivery() {
        new TransactionTemplate(txManager).execute(status -> {
            authService.requestPasswordReset("deliveryUser");
            status.setRollbackOnly();
            return null;
        });
        verify(deliveryService, never()).deliverAsync(anyString(), any(), anyString(), anyLong());
    }
}
