package com.rea.academy.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Map;

/**
 * 역할(Role) × 기능별 접근 권한 매트릭스.
 *
 * 규칙 요약:
 * - ACADEMY_OWNER(원장): 해당 학원 전체 읽기·쓰기
 * - TEACHER(강사): 읽기 전체 + 쓰기는 담당 반 관련만
 *   (학생 등록·출결·공지·상담·숙제·성적). 학원 설정·원장 전용 API는 읽기만
 * - PARENT(학부모): 본인(자녀) 범위 읽기 + 최소 쓰기 화이트리스트만 허용.
 *   모든 studentId/authKey 입력은 {@link ParentScope}가 본인 것으로 강제 치환한다.
 *
 * 상세 권한표는 docs/security.md 참조.
 */
public final class ReaAccessRules {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private ReaAccessRules() {
    }

    public static void check(HttpServletRequest request, AuthContext auth) {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        // ACADEMY_OWNER(원장): 학원 내 전체 허용 — 추가 검사 없음 (테넌트 격리는 인터셉터에서 별도 검사)
        if (auth.isTeacher()) {
            checkTeacher(method, uri);
        } else if (auth.isParent()) {
            checkParent(request, method, uri, auth);
        }
    }

    // ================= 강사 =================

    /**
     * 강사: 읽기 전체 허용. 쓰기는 아래 화이트리스트만 허용, 나머지는 403.
     * 화이트리스트 = 학생 등록·출결·공지·상담·숙제·성적 (+ 알림 읽음 처리 본인 확인용)
     */
    private static final List<WriteRule> TEACHER_WRITES = List.of(
            // 학생 등록
            WriteRule.of("POST", "/api/rea/*/students"),
            // 출결: 기록·키패드·정정
            WriteRule.of("POST", "/api/rea/*/attendance/check"),
            WriteRule.of("POST", "/api/rea/*/attendance/keypad"),
            WriteRule.of("PUT", "/api/rea/*/attendance/*"),
            // 공지: 작성·수정·삭제·읽음 처리
            WriteRule.of("POST", "/api/rea/*/notices"),
            WriteRule.of("PUT", "/api/rea/*/notices/*"),
            WriteRule.of("DELETE", "/api/rea/*/notices/*"),
            WriteRule.of("POST", "/api/rea/*/notices/*/read"),
            // 상담: 개설·메시지·종료·읽음 처리
            // (메시지/종료/읽음은 다중 세그먼트이므로 /** 매칭 — 단일 *는 /{id}/messages 등을 매칭하지 못함)
            WriteRule.of("POST", "/api/rea/*/consultations"),
            WriteRule.of("POST", "/api/rea/*/consultations/**"),
            // 숙제·진도·성적 (실제 컨트롤러 경로는 /learning 세그먼트 없음 — 프론트엔드와 동일)
            WriteRule.of("POST", "/api/rea/*/homework"),
            WriteRule.of("POST", "/api/rea/*/homework/*/check"),
            WriteRule.of("POST", "/api/rea/*/progress"),
            WriteRule.of("POST", "/api/rea/*/grades"),
            // 알림 읽음 처리 (본인 수신 확인용)
            WriteRule.of("POST", "/api/rea/*/notifications/*/read"),
            // 2단계 AI Agent 실행 (쓰기 도구 여부는 AgentExecutor가 별도 승인 검사)
            WriteRule.of("POST", "/api/rea/*/agent/execute"));

    private static void checkTeacher(String method, String uri) {
        // 출결 설정 변경은 원장 전용 (단일 세그먼트라 정정 PUT /attendance/{eventId} 패턴과
        // 구분할 수 없어 명시적으로 먼저 차단 — docs/security.md 3.2 권한표)
        if ("PUT".equalsIgnoreCase(method)
                && MATCHER.match("/api/rea/*/attendance/settings", uri)) {
            throw new ReaForbiddenException("출결 설정 변경은 원장 전용입니다.");
        }
        if (!isWrite(method)) {
            return; // 읽기 전체 허용
        }
        for (WriteRule rule : TEACHER_WRITES) {
            if (rule.matches(method, uri)) {
                return;
            }
        }
        throw new ReaForbiddenException("강사 권한으로 수행할 수 없는 작업입니다. (원장 전용)");
    }

    // ================= 학부모 =================

    /**
     * 학부모: 본인(자녀) 범위 읽기 + 아래 쓰기 화이트리스트만 허용, 나머지 쓰기 → 403.
     * 모든 studentId/authKey 입력은 {@link ParentScope}가 본인 것으로 강제 치환한다
     * (Service 레벨에서 강제 — 쿼리/본문 위조로 타 학생 데이터에 접근 불가).
     */
    private static final List<String> PARENT_OWN_STUDENT_READS = List.of(
            "/api/rea/*/students/{studentId}",
            "/api/rea/*/attendance/student/{studentId}",
            // 실제 컨트롤러 경로는 /learning 세그먼트 없음 — 프론트엔드와 동일
            "/api/rea/*/progress/student/{studentId}",
            "/api/rea/*/grades/student/{studentId}",
            "/api/rea/*/reports/student/{studentId}");

    private static final List<String> PARENT_NOTICE_READS = List.of(
            "/api/rea/*/notices",
            "/api/rea/*/notices/{id}");

    /** 본인 알림 조회 계열: 목록·미읽음 건수·알림 설정 조회 (발송·이력·재시도는 제외) */
    private static final List<String> PARENT_NOTIFICATION_READS = List.of(
            "/api/rea/*/notifications",
            "/api/rea/*/notifications/unread-count",
            "/api/rea/*/notifications/settings");

    /** 본인 상담 스레드 조회: 목록·상세 */
    private static final List<String> PARENT_CONSULTATION_READS = List.of(
            "/api/rea/*/consultations",
            "/api/rea/*/consultations/*");

    /**
     * 학부모 허용 쓰기 (본인 범위 한정 — Service에서 {@link ParentScope} 강제).
     * - POST /consultations: 학부모 문의 등록 (비투 핵심 흐름, INQUIRY만)
     * - POST /notices/{id}/read: 읽음 확인
     * - POST /attendance/keypad: 키오스크 출결 (본인 출석키로만 기록)
     */
    private static final List<WriteRule> PARENT_WRITES = List.of(
            WriteRule.of("POST", "/api/rea/*/consultations"),
            WriteRule.of("POST", "/api/rea/*/notices/*/read"),
            WriteRule.of("POST", "/api/rea/*/attendance/keypad"));

    private static void checkParent(HttpServletRequest request, String method,
                                    String uri, AuthContext auth) {
        if (isWrite(method)) {
            for (WriteRule rule : PARENT_WRITES) {
                if (rule.matches(method, uri)) {
                    return;
                }
            }
            throw new ReaForbiddenException("학부모 권한으로 수행할 수 없는 작업입니다.");
        }
        // 본인 자녀 데이터 (경로상 studentId가 본인 것과 다르면 403)
        for (String pattern : PARENT_OWN_STUDENT_READS) {
            if (MATCHER.match(pattern, uri)) {
                Map<String, String> vars = MATCHER.extractUriTemplateVariables(pattern, uri);
                long studentId = Long.parseLong(vars.get("studentId"));
                if (studentId != auth.ownStudentId()) {
                    throw new ReaForbiddenException("다른 학생의 데이터는 조회할 수 없습니다.");
                }
                return;
            }
        }
        // 학원 공지 (학부모 뷰는 Service에서 X-Auth-Key 기준 자동 판정 — NTC-04)
        for (String pattern : PARENT_NOTICE_READS) {
            if (MATCHER.match(pattern, uri)) {
                return;
            }
        }
        // 본인 알림·상담 조회 (본인 범위는 Service에서 ParentScope 강제)
        for (String pattern : PARENT_NOTIFICATION_READS) {
            if (MATCHER.match(pattern, uri)) {
                return;
            }
        }
        for (String pattern : PARENT_CONSULTATION_READS) {
            if (MATCHER.match(pattern, uri)) {
                return;
            }
        }
        throw new ReaForbiddenException("학부모 권한으로 조회할 수 없는 정보입니다.");
    }

    // ================= 공통 =================

    private static boolean isWrite(String method) {
        return !"GET".equalsIgnoreCase(method)
                && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method);
    }

    private record WriteRule(String method, String pattern) {
        static WriteRule of(String method, String pattern) {
            return new WriteRule(method, pattern);
        }

        boolean matches(String reqMethod, String uri) {
            return method.equalsIgnoreCase(reqMethod) && MATCHER.match(pattern, uri);
        }
    }
}
