package com.rea.academy.security;

/**
 * 학부모(학생 인증키로 로그인) 본인-스코프 강제 헬퍼.
 *
 * <p>학부모는 {@code X-Auth-Key}에 자녀(학생) 인증키를 담아 로그인하므로,
 * 백엔드는 인증 컨텍스트에서 본인(자녀) 학생 ID를 역으로 알 수 있다.
 * 학부모가 요청 파라미터·본문에 다른 학생의 ID/인증키를 담아 보내더라도
 * 본인 것으로 강제 치환함으로써 타 학생 데이터 접근을 원천 차단한다.</p>
 *
 * <p>원장·강사(MEMBER principal) 인증에는 아무 것도 하지 않는다
 * (기존 쿼리 파라미터·본문 방식 그대로 — 하위 호환).</p>
 */
public final class ParentScope {

    private ParentScope() {
    }

    /**
     * 학생 키로 로그인한 학부모이면 본인(자녀) 학생 ID, 아니면 null.
     */
    public static Long ownStudentIdOrNull() {
        AuthContext auth = AuthContextHolder.getOrNull();
        if (auth != null && auth.principalType() == AuthContext.PrincipalType.STUDENT) {
            return auth.ownStudentId();
        }
        return null;
    }

    /** 본인 강제 적용 결과: (studentId, authKey). 학부모가 아니면 입력 그대로. */
    public record StudentRef(Long studentId, String authKey) {
    }

    /**
     * 본인 강제: 학생 키 로그인(학부모)이면 전달된 studentId/authKey를 무시하고
     * 본인(자녀) 학생 ID로 대체한다. 원장·강사 인증이면 입력 그대로 반환.
     */
    public static StudentRef forceOwnStudent(Long studentId, String authKey) {
        Long own = ownStudentIdOrNull();
        return own != null ? new StudentRef(own, null) : new StudentRef(studentId, authKey);
    }
}
