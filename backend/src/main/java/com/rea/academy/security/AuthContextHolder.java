package com.rea.academy.security;

/**
 * 요청 스레드별 인증 컨텍스트 보관소 (ThreadLocal).
 *
 * {@link AuthKeyFilter}가 인증 성공 시 저장하고, 요청 종료 시 반드시 제거한다.
 * 컨트롤러·인터셉터·서비스에서 현재 요청의 인증 주체를 조회할 때 사용한다.
 */
public final class AuthContextHolder {

    private static final ThreadLocal<AuthContext> HOLDER = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    public static void set(AuthContext context) {
        HOLDER.set(context);
    }

    /**
     * 현재 인증 컨텍스트 반환. 없으면 401 예외.
     */
    public static AuthContext get() {
        AuthContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new ReaAuthenticationException("인증이 필요합니다.");
        }
        return ctx;
    }

    /**
     * 현재 인증 컨텍스트 반환. 없으면 null (공개 경로 등에서 사용).
     */
    public static AuthContext getOrNull() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
