package com.rea.academy.notification;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.notification.delivery.DeliveryResult;
import com.rea.academy.domain.notification.delivery.PasswordResetDeliveryService;
import com.rea.academy.domain.notification.delivery.PasswordResetRateLimiter;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.service.AuthService;
import com.rea.academy.service.MemberService;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비밀번호 재설정 발송 통합 테스트 (MockMvc + Mock 발송 서비스).
 *
 * <p>실제 외부 발송 없음. 검증:
 * 발송 호출·계정 존재 여부 비노출·요청 제한·발송 실패 시에도 토큰 유효.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
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

    @MockBean
    private PasswordResetDeliveryService deliveryService;

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
        when(deliveryService.deliver(anyString(), any(), anyString()))
                .thenReturn(DeliveryResult.success("SMS", "mock-1"));
        Long memberId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(1L).name("발송테스트").phone("010-1234-5678")
                .role(com.rea.academy.domain.member.ReaRole.TEACHER).build()).getId();
        String authKey = memberRepository.findById(memberId).orElseThrow().getAuthKey();
        authService.setupCredentials(authKey, "deliveryUser", "Pass1234!");
    }

    @Test
    @DisplayName("존재하는 계정 → 발송 서비스 호출, 응답 메시지는 고정")
    void existingAccount_delivers() throws Exception {
        String body = requestBody("deliveryUser");
        assertThat(body).contains("재설정 안내를 발송했습니다");
        verify(deliveryService, times(1))
                .deliver(eq("010-1234-5678"), isNull(), anyString());
    }

    @Test
    @DisplayName("존재하지 않는 계정 → 발송 없음, 응답은 동일 (누설 방지)")
    void unknownAccount_sameResponse_noDelivery() throws Exception {
        String bodyKnown = requestBody("deliveryUser");
        String bodyUnknown = requestBody("nobody123");
        // 메시지만 비교 (resetToken은 개발 환경에서 포함되므로 제외)
        assertThat(bodyUnknown).contains("재설정 안내를 발송했습니다");
        assertThat(bodyKnown).contains("재설정 안내를 발송했습니다");
        // 존재하는 계정에 대해서만 1회 호출
        verify(deliveryService, times(1)).deliver(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("발송 실패해도 200 반환, 토큰은 유효하게 유지 (재요청 가능)")
    void deliveryFailure_tokenStillValid() throws Exception {
        when(deliveryService.deliver(anyString(), any(), anyString()))
                .thenReturn(DeliveryResult.failure("SMS", "SOLAPI 오류"));
        String body = requestBody("deliveryUser");
        assertThat(body).contains("재설정 안내를 발송했습니다");

        // 토큰 추출 후 confirm 가능 = 토큰이 유효하게 유지됨
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
        // 6회째는 발송 호출 없음 (토큰 미발급)
        verify(deliveryService, times(5)).deliver(anyString(), any(), anyString());
        // 6회째 응답에는 resetToken 없음
        assertThat(body6).doesNotContain("\"resetToken\":\"");
    }
}
