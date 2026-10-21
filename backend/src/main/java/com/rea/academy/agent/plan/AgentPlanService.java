package com.rea.academy.agent.plan;

import com.rea.academy.common.ReaTime;
import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentDispatcher;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.domain.admin.ReaAcademyRepository;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.AgentDto;
import com.rea.academy.security.AuthContext;
import com.rea.academy.service.AuditRecorder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 서버 검증형 AI 쓰기 승인 (P1-a).
 *
 * <p>쓰기 도구는 요청 한 번으로 실행되지 않는다.
 * <ol>
 *   <li>{@link #createPlan}: 입력을 정규화·검증하고 서버가 미리보기를 만들어 계획을 저장한다 (데이터 무변경).
 *   <li>{@link #approve}: 계획 ID + 사용자가 본 계획 해시만 받는다. 사용자·학원·역할·연결·도구·입력·대상을
 *       서버가 다시 검증하고, 조건부 갱신(CAS)으로 단 한 번만 실행한다. 실행 입력은 저장된 값뿐이다.
 * </ol>
 * 쓰기 실패는 자동 재시도하지 않는다. 다시 하려면 새 계획과 새 승인이 필요하다.
 */
@Service
public class AgentPlanService {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int MAX_INPUT_JSON = 3800;
    private static final int MAX_PREVIEW_JSON = 4000;
    private static final int MAX_RESULT_JSON = 2000;
    private static final int MAX_STRING = 2000;
    private static final Set<String> SECRET_KEYS = Set.of("authKey", "password", "token", "secret");

    private final ReaAgentPlanRepository plans;
    private final AgentDispatcher dispatcher;
    private final AuditRecorder auditRecorder;
    private final ReaMemberRepository members;
    private final ReaAcademyRepository academies;
    private final AgentPlanProperties props;
    private final TransactionTemplate tx;

    public AgentPlanService(ReaAgentPlanRepository plans, AgentDispatcher dispatcher, AuditRecorder auditRecorder,
                            ReaMemberRepository members, ReaAcademyRepository academies,
                            AgentPlanProperties props, PlatformTransactionManager txManager) {
        this.plans = plans;
        this.dispatcher = dispatcher;
        this.auditRecorder = auditRecorder;
        this.members = members;
        this.academies = academies;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    // ============================== 입력 정규화 ==============================

    /**
     * 도구 입력 정규화: 스키마에 있는 키만, 스칼라 값만 허용하고 키 순서를 고정한다.
     * 모델이 만든 파라미터를 그대로 믿지 않기 위한 1차 검증이다 (대상 소속 검증은 tool.preview 가 수행).
     */
    public Map<String, Object> normalizeInput(AgentTool tool, Map<String, Object> raw) {
        Set<String> allowed = new HashSet<>();
        for (String key : tool.getInputSchema().keySet()) {
            allowed.add(key.endsWith("*") ? key.substring(0, key.length() - 1) : key);
        }
        Map<String, Object> out = new TreeMap<>();
        if (raw != null) {
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                String key = e.getKey();
                if (key == null || key.length() > 50 || !allowed.contains(key)) {
                    throw new IllegalArgumentException("허용되지 않은 입력 항목입니다: " + key);
                }
                Object v = e.getValue();
                if (v == null) {
                    continue;
                }
                if (v instanceof String s) {
                    String t = s.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (t.length() > MAX_STRING) {
                        throw new IllegalArgumentException("입력이 너무 깁니다: " + key);
                    }
                    out.put(key, t);
                } else if (v instanceof Boolean) {
                    out.put(key, v);
                } else if (v instanceof Number n) {
                    if (v instanceof Double || v instanceof Float || v instanceof java.math.BigDecimal) {
                        double d = n.doubleValue();
                        if (d != Math.rint(d) || Double.isInfinite(d)) {
                            throw new IllegalArgumentException("정수만 입력할 수 있습니다: " + key);
                        }
                    }
                    out.put(key, n.longValue());
                } else {
                    throw new IllegalArgumentException("지원하지 않는 값 형식입니다: " + key);
                }
            }
        }
        if (AgentPlanHasher.canonicalJson(out).length() > MAX_INPUT_JSON) {
            throw new IllegalArgumentException("입력이 너무 큽니다.");
        }
        return out;
    }

    // ============================== 1단계: 계획 생성 ==============================

    /**
     * 쓰기 도구 계획 생성. 데이터는 바뀌지 않는다. 호출자의 트랜잭션에 참여한다(없으면 새로 시작).
     * 거부·검증 실패 같은 예상된 업무 예외로는 트랜잭션을 롤백 전용으로 만들지 않는다 —
     * 호출자(AgentExecutor)가 예외를 응답으로 바꾸고, 그 거부 감사 로그도 함께 커밋되어야 하기 때문이다.
     */
    @Transactional(noRollbackFor = {AgentPlanException.class, IllegalArgumentException.class,
            IllegalStateException.class, UnsupportedOperationException.class})
    public AgentDto.PlanView createPlan(AgentContext ctx, AgentTool tool, Map<String, Object> rawInput,
                                        String idempotencyKey, String source, String authSource) {
        if (!props.enabled()) {
            throw reject(ctx, tool.getName(), HttpStatus.SERVICE_UNAVAILABLE, "APPROVAL_DISABLED",
                    "AI 쓰기 기능이 일시 중지되었습니다. 관리 화면에서 직접 처리해 주세요.");
        }
        if (!AgentToolPolicy.mayUseAiWrite(ctx)) {
            throw reject(ctx, tool.getName(), HttpStatus.FORBIDDEN, "OWNER_ONLY",
                    "AI 쓰기는 원장만 사용할 수 있습니다.");
        }
        if (!tool.isWrite()) {
            throw new IllegalArgumentException("쓰기 도구가 아닙니다: " + tool.getName());
        }
        Map<String, Object> input = normalizeInput(tool, rawInput);
        String inputJson = AgentPlanHasher.canonicalJson(input);
        String inputSha = AgentPlanHasher.sha256Hex(inputJson);

        // 같은 원장의 생성 요청을 직렬화한다: 아래 중복 키 검사·상한 검사·저장이 경합 없이 이어진다.
        // (별도 트랜잭션(REQUIRES_NEW)은 연결 풀 고갈 위험이 있어 쓰지 않는다)
        plans.lockMemberForPlanCreation(ctx.principalId());

        if (idempotencyKey != null) {
            if (!UUID_PATTERN.matcher(idempotencyKey).matches()) {
                throw new IllegalArgumentException("idempotencyKey 는 UUID 형식이어야 합니다.");
            }
            Optional<ReaAgentPlan> existing = plans.findByAcademyIdAndIdempotencyKey(ctx.academyId(), idempotencyKey);
            if (existing.isPresent()) {
                ReaAgentPlan e = existing.get();
                if (!e.getPrincipalId().equals(ctx.principalId()) || !e.getToolName().equals(tool.getName())
                        || !e.getInputSha256().equals(inputSha)) {
                    throw reject(ctx, tool.getName(), HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                            "같은 idempotencyKey 로 다른 요청을 보낼 수 없습니다.");
                }
                AgentDto.PlanView view = view(e, null, null);
                view.setReplayed(true);
                return view;
            }
        }

        Map<String, Object> preview = tool.preview(input, ctx);
        String previewJson = AgentPlanHasher.canonicalJson(preview);
        if (previewJson.length() > MAX_PREVIEW_JSON) {
            throw new IllegalArgumentException("미리보기가 너무 큽니다. 요청을 나누어 주세요.");
        }

        LocalDateTime now = ReaTime.now().truncatedTo(ChronoUnit.MILLIS);
        if (plans.countByAcademyIdAndPrincipalIdAndStatusAndExpiresAtAfter(
                ctx.academyId(), ctx.principalId(), AgentPlanStatus.PENDING, now) >= props.maxPendingPerPrincipal()) {
            throw reject(ctx, tool.getName(), HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_PENDING",
                    "승인 대기 중인 계획이 너무 많습니다. 기존 계획을 승인하거나 취소해 주세요.");
        }
        if (plans.countByAcademyIdAndPrincipalIdAndCreatedAtAfter(
                ctx.academyId(), ctx.principalId(), now.minusMinutes(1)) >= props.maxCreatesPerMinute()) {
            throw reject(ctx, tool.getName(), HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        }

        LocalDateTime expiresAt = now.plus(props.ttl());
        String fingerprint = AgentPlanHasher.fingerprint(ctx, authSource);
        String role = ctx.role().name();
        String hash = AgentPlanHasher.planHash(ctx.academyId(), ctx.principalId(), role, fingerprint,
                tool.getName(), inputSha, AgentPlanHasher.sha256Hex(previewJson), expiresAt);
        ReaAgentPlan plan = ReaAgentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .academyId(ctx.academyId())
                .principalId(ctx.principalId())
                .roleAtPlan(role)
                .bindingFingerprint(fingerprint)
                .toolName(tool.getName())
                .inputJson(inputJson)
                .inputSha256(inputSha)
                .previewJson(previewJson)
                .planHash(hash)
                .source(source)
                .status(AgentPlanStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();
        try {
            plans.saveAndFlush(plan);
        } catch (DataIntegrityViolationException e) {
            // 잠금으로 직렬화되므로 정상 흐름에서는 발생하지 않는다 (유니크 제약은 최후 방어선)
            throw new AgentPlanException(HttpStatus.CONFLICT, "IDEMPOTENCY_RACE",
                    "같은 요청이 동시에 처리되었습니다. 잠시 후 같은 요청을 다시 보내 주세요.");
        }
        audit(AuditActionType.AGENT_PLAN_CREATED, ctx, plan, "source=" + source);
        return view(plan, null, "승인 전에는 아무것도 저장되지 않습니다.");
    }

    // ============================== 조회 ==============================

    public AgentDto.PlanView get(AgentContext ctx, String planId) {
        requireUuid(planId);
        Outcome out = tx.execute(st -> {
            ReaAgentPlan plan = plans.findForUpdate(planId).orElse(null);
            if (plan == null || !visibleTo(ctx, plan)) {
                return Outcome.error(new AgentPlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
            }
            applyLazyTransitions(ctx, plan);
            return Outcome.view(view(plan, null, null));
        });
        return out.unwrap();
    }

    // ============================== 취소 ==============================

    public AgentDto.PlanView cancel(AgentContext ctx, String planId) {
        requireUuid(planId);
        Outcome out = tx.execute(st -> {
            ReaAgentPlan plan = plans.findForUpdate(planId).orElse(null);
            if (plan == null || !visibleTo(ctx, plan)) {
                return Outcome.error(new AgentPlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
            }
            applyLazyTransitions(ctx, plan);
            if (plan.getStatus() != AgentPlanStatus.PENDING) {
                return Outcome.error(new AgentPlanException(HttpStatus.CONFLICT, "PLAN_" + plan.getStatus(),
                        "취소할 수 없는 상태입니다: " + plan.getStatus()));
            }
            plan.markCancelled();
            plans.save(plan);
            audit(AuditActionType.AGENT_PLAN_CANCELLED, ctx, plan, "user_cancel");
            return Outcome.view(view(plan, null, "취소했습니다. 바뀐 내용은 없습니다."));
        });
        return out.unwrap();
    }

    // ============================== 2단계: 승인·실행 ==============================

    /**
     * 승인 후 단 한 번 실행한다. 요청에는 입력이 없다 — 실행 입력은 저장된 계획의 값뿐이다.
     *
     * @param planHash 사용자가 본 계획의 해시 (미리보기와 함께 받은 값)
     */
    public AgentDto.PlanView approve(AgentContext ctx, String planId, String planHash, String authSource) {
        requireUuid(planId);
        Gate gate = tx.execute(st -> gate(ctx, planId, planHash, authSource));
        if (gate.error != null) {
            throw gate.error;
        }
        if (gate.replay != null) {
            return gate.replay;
        }

        // 실행은 별도 트랜잭션 경계 밖에서 한다 (도구가 기존 서비스의 @Transactional 을 사용).
        // 도구에는 저장된 입력만 전달하고, 쓰기 승인은 서버가 내부에서만 부여한다.
        Map<String, Object> result;
        try {
            @SuppressWarnings("deprecation")
            AgentContext execCtx = new AgentContext(ctx.principalType(), ctx.academyId(), ctx.role(),
                    ctx.principalId(), ctx.principalName(), true);
            result = gate.tool.execute(gate.input, execCtx);
        } catch (RuntimeException e) {
            String reason = truncate(e.getMessage(), 200);
            Outcome failed = tx.execute(st -> {
                ReaAgentPlan plan = plans.findForUpdate(planId).orElseThrow();
                plan.markFailed("TOOL_ERROR", ReaTime.now().truncatedTo(ChronoUnit.MILLIS));
                plans.save(plan);
                audit(AuditActionType.AGENT_PLAN_FAILED, ctx, plan, "failure=TOOL_ERROR");
                AgentDto.PlanView v = view(plan, null,
                        "실행하지 못했습니다. 자동으로 다시 시도하지 않습니다. 필요하면 새로 요청해 주세요.");
                v.setFailureCode("TOOL_ERROR");
                return Outcome.view(v);
            });
            AgentDto.PlanView v = failed.unwrap();
            v.setMessage(v.getMessage() + (reason == null ? "" : " (" + reason + ")"));
            return v;
        }

        Outcome done = tx.execute(st -> {
            ReaAgentPlan plan = plans.findForUpdate(planId).orElseThrow();
            Map<String, Object> redacted = redact(result);
            String json = AgentPlanHasher.canonicalJson(redacted);
            if (json.length() > MAX_RESULT_JSON) {
                json = AgentPlanHasher.canonicalJson(Map.of("truncated", true));
            }
            plan.markExecuted(json, ReaTime.now().truncatedTo(ChronoUnit.MILLIS));
            plans.save(plan);
            audit(AuditActionType.AGENT_PLAN_EXECUTED, ctx, plan, "ok");
            return Outcome.view(view(plan, result, "실행했습니다."));
        });
        return done.unwrap();
    }

    /** 승인 전 검증 + 1회 실행 선점(CAS). 오류는 상태를 커밋한 뒤 호출자가 던진다. */
    private Gate gate(AgentContext ctx, String planId, String requestHash, String authSource) {
        ReaAgentPlan plan = plans.findForUpdate(planId).orElse(null);
        if (plan == null) {
            return Gate.error(new AgentPlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
        }
        // 다른 학원의 계획은 존재 자체를 숨긴다
        if (!plan.getAcademyId().equals(ctx.academyId())) {
            auditRaw(AuditActionType.AGENT_PLAN_REJECTED, ctx, "plan=" + planId + " reason=ACADEMY_MISMATCH");
            return Gate.error(new AgentPlanException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "계획을 찾을 수 없습니다."));
        }
        // 계획을 만든 본인만 승인할 수 있다
        if (ctx.principalType() != AuthContext.PrincipalType.MEMBER || !plan.getPrincipalId().equals(ctx.principalId())) {
            audit(AuditActionType.AGENT_PLAN_REJECTED, ctx, plan, "reason=NOT_PLAN_OWNER");
            return Gate.error(new AgentPlanException(HttpStatus.FORBIDDEN, "NOT_PLAN_OWNER", "계획을 만든 본인만 승인할 수 있습니다."));
        }

        applyLazyTransitions(ctx, plan);
        switch (plan.getStatus()) {
            case EXECUTED -> {
                AgentDto.PlanView replay = view(plan, null, "이미 실행된 계획입니다. 다시 실행하지 않았습니다.");
                replay.setReplayed(true);
                return Gate.replay(replay);
            }
            case EXECUTING -> {
                return Gate.error(new AgentPlanException(HttpStatus.CONFLICT, "PLAN_IN_PROGRESS", "실행 중인 계획입니다."));
            }
            case EXPIRED -> {
                return Gate.error(new AgentPlanException(HttpStatus.GONE, "PLAN_EXPIRED", "시간이 지나 만료된 계획입니다. 다시 요청해 주세요."));
            }
            case PENDING -> { /* 계속 */ }
            default -> {
                return Gate.error(new AgentPlanException(HttpStatus.CONFLICT, "PLAN_" + plan.getStatus(),
                        "승인할 수 없는 상태입니다: " + plan.getStatus()));
            }
        }

        if (!props.enabled()) {
            audit(AuditActionType.AGENT_PLAN_REJECTED, ctx, plan, "reason=APPROVAL_DISABLED");
            return Gate.error(new AgentPlanException(HttpStatus.SERVICE_UNAVAILABLE, "APPROVAL_DISABLED",
                    "AI 쓰기 기능이 일시 중지되었습니다."));
        }

        // 실제 연결 회원·역할·학원 재검증 (권한 변경·학원 변경 시 승인 무효화)
        String invalid = liveCheck(ctx, plan, authSource);
        if (invalid != null) {
            return Gate.error(invalidate(ctx, plan, invalid, HttpStatus.FORBIDDEN));
        }

        // 저장 행 변조 탐지: 저장된 입력·미리보기의 해시와 계획 해시를 다시 계산한다
        String recomputedInputSha = AgentPlanHasher.sha256Hex(plan.getInputJson());
        String recomputed = AgentPlanHasher.planHash(plan.getAcademyId(), plan.getPrincipalId(), plan.getRoleAtPlan(),
                plan.getBindingFingerprint(), plan.getToolName(), recomputedInputSha,
                AgentPlanHasher.sha256Hex(plan.getPreviewJson()), plan.getExpiresAt());
        if (!recomputedInputSha.equals(plan.getInputSha256())
                || !AgentPlanHasher.constantTimeEquals(recomputed, plan.getPlanHash())) {
            return Gate.error(invalidate(ctx, plan, "PLAN_TAMPERED", HttpStatus.FORBIDDEN));
        }
        // 사용자가 본 계획인지 확인 (틀린 해시는 거부만 하고 계획은 유지)
        if (!AgentPlanHasher.constantTimeEquals(requestHash, plan.getPlanHash())) {
            audit(AuditActionType.AGENT_PLAN_REJECTED, ctx, plan, "reason=HASH_MISMATCH");
            return Gate.error(new AgentPlanException(HttpStatus.FORBIDDEN, "HASH_MISMATCH",
                    "승인 요청이 보신 계획과 일치하지 않습니다. 계획을 다시 확인해 주세요."));
        }

        // 도구·입력·대상 재검증
        AgentTool tool;
        try {
            tool = dispatcher.resolve(plan.getToolName());
        } catch (IllegalArgumentException e) {
            return Gate.error(invalidate(ctx, plan, "TOOL_UNAVAILABLE", HttpStatus.UNPROCESSABLE_ENTITY));
        }
        if (!tool.isWrite()) {
            return Gate.error(invalidate(ctx, plan, "TOOL_NOT_WRITE", HttpStatus.UNPROCESSABLE_ENTITY));
        }
        Map<String, Object> input;
        try {
            input = normalizeInput(tool, AgentPlanHasher.parseMap(plan.getInputJson()));
            if (!AgentPlanHasher.canonicalJson(input).equals(plan.getInputJson())) {
                return Gate.error(invalidate(ctx, plan, "PLAN_TAMPERED", HttpStatus.FORBIDDEN));
            }
            String freshPreview = AgentPlanHasher.canonicalJson(tool.preview(input, ctx));
            if (!freshPreview.equals(plan.getPreviewJson())) {
                return Gate.error(invalidate(ctx, plan, "TARGET_CHANGED", HttpStatus.CONFLICT));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Gate.error(invalidate(ctx, plan, "TARGET_CHANGED", HttpStatus.CONFLICT));
        }

        // 1회 실행 선점(CAS): PENDING → EXECUTING 은 한 요청만 성공한다
        int claimed = plans.claimForExecution(plan.getPlanId(), ctx.principalId(),
                ReaTime.now().truncatedTo(ChronoUnit.MILLIS));
        if (claimed != 1) {
            return Gate.error(new AgentPlanException(HttpStatus.CONFLICT, "PLAN_CONFLICT",
                    "다른 요청이 먼저 처리했거나 만료된 계획입니다."));
        }
        audit(AuditActionType.AGENT_PLAN_APPROVED, ctx, plan, "approved");
        return Gate.go(tool, input);
    }

    /** 현재 연결 회원·역할·학원·인증 경로가 계획 시점과 같은지 확인. 문제가 있으면 사유 코드, 없으면 null. */
    private String liveCheck(AgentContext ctx, ReaAgentPlan plan, String authSource) {
        if (!AgentToolPolicy.mayUseAiWrite(ctx)) {
            return "ROLE_CHANGED";
        }
        var member = members.findById(plan.getPrincipalId()).orElse(null);
        if (member == null || !Boolean.TRUE.equals(member.getActive())
                || member.getRole() != ReaRole.ACADEMY_OWNER
                || !plan.getAcademyId().equals(member.getAcademyId())) {
            return "MEMBER_CHANGED";
        }
        if (!academies.findById(plan.getAcademyId()).map(a -> Boolean.TRUE.equals(a.getActive())).orElse(false)) {
            return "ACADEMY_INACTIVE";
        }
        if (!plan.getRoleAtPlan().equals(ctx.role().name())
                || !plan.getBindingFingerprint().equals(AgentPlanHasher.fingerprint(ctx, authSource))) {
            return "CONNECTION_CHANGED";
        }
        return null;
    }

    private AgentPlanException invalidate(AgentContext ctx, ReaAgentPlan plan, String code, HttpStatus status) {
        plan.markTerminal(AgentPlanStatus.INVALIDATED, code);
        plans.save(plan);
        audit(AuditActionType.AGENT_PLAN_INVALIDATED, ctx, plan, "reason=" + code);
        return new AgentPlanException(status, code, invalidMessage(code));
    }

    private static String invalidMessage(String code) {
        return switch (code) {
            case "TARGET_CHANGED" -> "계획을 만든 뒤 대상이 바뀌어 무효가 되었습니다. 다시 요청해 주세요.";
            case "ROLE_CHANGED", "MEMBER_CHANGED", "CONNECTION_CHANGED", "ACADEMY_INACTIVE" ->
                    "권한 또는 학원 연결이 바뀌어 계획이 무효가 되었습니다.";
            default -> "계획을 실행할 수 없어 무효 처리했습니다.";
        };
    }

    /** 만료·장기 실행 중 상태를 조회 시점에 정리한다 (잠금을 쥔 트랜잭션 안에서 호출). */
    private void applyLazyTransitions(AgentContext ctx, ReaAgentPlan plan) {
        LocalDateTime now = ReaTime.now().truncatedTo(ChronoUnit.MILLIS);
        if (plan.getStatus() == AgentPlanStatus.PENDING && !now.isBefore(plan.getExpiresAt())) {
            plan.markTerminal(AgentPlanStatus.EXPIRED, null);
            plans.save(plan);
            audit(AuditActionType.AGENT_PLAN_EXPIRED, ctx, plan, "expired");
        } else if (plan.getStatus() == AgentPlanStatus.EXECUTING && plan.getApprovedAt() != null
                && plan.getApprovedAt().plus(props.executingTimeout()).isBefore(now)) {
            // 실행 중 프로세스가 중단된 경우: 일부 반영됐을 수 있으므로 재실행하지 않고 실패(결과 불명)로 표시
            plan.markFailed("UNKNOWN_OUTCOME", now);
            plans.save(plan);
            audit(AuditActionType.AGENT_PLAN_FAILED, ctx, plan, "failure=UNKNOWN_OUTCOME");
        }
    }

    // ============================== 보조 ==============================

    /** 계획은 만든 본인에게만 보인다 (다른 학원·다른 사용자는 존재를 숨긴다). */
    private static boolean visibleTo(AgentContext ctx, ReaAgentPlan plan) {
        return plan.getAcademyId().equals(ctx.academyId())
                && ctx.principalType() == AuthContext.PrincipalType.MEMBER
                && plan.getPrincipalId().equals(ctx.principalId());
    }

    private static void requireUuid(String planId) {
        if (planId == null || !UUID_PATTERN.matcher(planId).matches()) {
            throw new IllegalArgumentException("planId 형식이 올바르지 않습니다.");
        }
    }

    private AgentDto.PlanView view(ReaAgentPlan plan, Map<String, Object> liveResult, String message) {
        Map<String, Object> result = liveResult;
        if (result == null && plan.getResultJson() != null) {
            result = AgentPlanHasher.parseMap(plan.getResultJson());
        }
        return AgentDto.PlanView.builder()
                .planId(plan.getPlanId())
                .status(plan.getStatus().name())
                .toolName(plan.getToolName())
                .preview(AgentPlanHasher.parseMap(plan.getPreviewJson()))
                .planHash(plan.getPlanHash())
                .expiresAt(plan.getExpiresAt())
                .result(result)
                .failureCode(plan.getFailureCode())
                .message(message)
                .build();
    }

    private static Map<String, Object> redact(Map<String, Object> result) {
        Map<String, Object> copy = new TreeMap<>();
        if (result != null) {
            result.forEach((k, v) -> {
                if (!SECRET_KEYS.contains(k)) {
                    copy.put(k, v);
                }
            });
        }
        return copy;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 거부 감사를 남기고 예외를 만든다. 호출자(트랜잭션)가 커밋되도록 예외는 호출자에서 처리한다. */
    private AgentPlanException reject(AgentContext ctx, String toolName, HttpStatus status, String code, String message) {
        auditRaw(AuditActionType.AGENT_PLAN_REJECTED, ctx, "tool=" + toolName + " reason=" + code);
        return new AgentPlanException(status, code, message);
    }

    /** 외부에서 쓰기 승인 우회(예: writeApproved=true)를 시도했을 때 기록한다. */
    public void recordRejected(AgentContext ctx, String detail) {
        auditRaw(AuditActionType.AGENT_PLAN_REJECTED, ctx, detail);
    }

    private void audit(AuditActionType type, AgentContext ctx, ReaAgentPlan plan, String extra) {
        auditRecorder.record(plan.getAcademyId(), type, "에이전트", null, plan.getToolName(),
                ctx.principalId(), ctx.principalName(),
                "plan=" + plan.getPlanId() + " tool=" + plan.getToolName()
                        + " input=" + plan.getInputSha256().substring(0, 12) + " " + extra);
    }

    private void auditRaw(AuditActionType type, AgentContext ctx, String detail) {
        auditRecorder.record(ctx.academyId(), type, "에이전트", null, null,
                ctx.principalId(), ctx.principalName(), truncate(detail, 500));
    }

    // ----- 내부 결과 운반 객체 (트랜잭션 커밋 후 던지기 위함) -----

    private record Outcome(AgentDto.PlanView view, AgentPlanException error) {
        static Outcome view(AgentDto.PlanView v) { return new Outcome(v, null); }
        static Outcome error(AgentPlanException e) { return new Outcome(null, e); }
        AgentDto.PlanView unwrap() {
            if (error != null) {
                throw error;
            }
            return view;
        }
    }

    private static final class Gate {
        final AgentPlanException error;
        final AgentDto.PlanView replay;
        final AgentTool tool;
        final Map<String, Object> input;

        private Gate(AgentPlanException error, AgentDto.PlanView replay, AgentTool tool, Map<String, Object> input) {
            this.error = error;
            this.replay = replay;
            this.tool = tool;
            this.input = input;
        }

        static Gate error(AgentPlanException e) { return new Gate(e, null, null, null); }
        static Gate replay(AgentDto.PlanView v) { return new Gate(null, v, null, null); }
        static Gate go(AgentTool t, Map<String, Object> in) { return new Gate(null, null, t, in); }
    }
}
