package com.rea.academy.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 테넌트 격리 + 역할 권한 강제 인터셉터.
 *
 * 1. 테넌트 격리: URL의 {academyId}와 인증된 사용자의 academyId가 다르면 403.
 *    (Service 레벨의 academyId 스코핑과 이중 방어)
 * 2. 역할 권한: {@link ReaAccessRules}의 역할 × 기능 매트릭스 적용.
 *
 * 인증키 로그인(/api/rea/auth/**) 등 인증 컨텍스트가 없는 공개 경로는 통과시킨다.
 * (인증 자체는 앞단의 {@link AuthKeyFilter}가 담당 — 누락·무효 키는 401로 이미 차단됨)
 */
@Component
public class ReaSecurityInterceptor implements HandlerInterceptor {

    @SuppressWarnings("unchecked")
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        AuthContext auth = AuthContextHolder.getOrNull();
        if (auth == null) {
            return true; // 공개 경로 (key-login 등)
        }

        if ("GET".equals(request.getMethod()) && "/api/rea/integration/context".equals(request.getRequestURI())) return true;
        // 1. 테넌트 격리: URL {academyId} vs 인증 사용자의 academyId
        if (handler instanceof HandlerMethod) {
            Map<String, String> vars = (Map<String, String>) request
                    .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (vars != null && vars.containsKey("academyId")) {
                long pathAcademyId = Long.parseLong(vars.get("academyId"));
                if (pathAcademyId != auth.academyId()) {
                    throw new ReaForbiddenException(
                            "다른 학원(academyId=" + pathAcademyId + ")의 데이터에는 접근할 수 없습니다.");
                }
            }
        }

        // 2. 역할별 접근 권한
        ReaAccessRules.check(request, auth);
        return true;
    }
}
