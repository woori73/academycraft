package com.rea.academy.agent.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.rea.academy.agent.AgentContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/** 정규화 JSON·해시 계산. 같은 값은 항상 같은 문자열·해시가 되어야 한다(키 정렬). */
public final class AgentPlanHasher {

    private static final String VERSION = "v1";
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    private AgentPlanHasher() {
    }

    public static String canonicalJson(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 직렬화에 실패했습니다.");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String json) {
        try {
            return CANONICAL.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("저장된 JSON을 읽을 수 없습니다.");
        }
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 인증 경로·연결 지문. 역할·학원·주체·BuildIt 경유 여부와 BuildIt 역할이 바뀌면 값이 달라진다.
     * authSource 예: "DIRECT"(자체 로그인·인증키), "BUILDIT:ADMIN", "BUILDIT:TUTOR".
     */
    public static String fingerprint(AgentContext ctx, String authSource) {
        return sha256Hex(String.join("|", VERSION, String.valueOf(ctx.principalType()),
                String.valueOf(ctx.principalId()), String.valueOf(ctx.academyId()),
                String.valueOf(ctx.role()), authSource == null ? "DIRECT" : authSource));
    }

    /** 사용자·학원·역할·연결·도구·입력·미리보기·만료를 하나로 묶는 계획 해시 */
    public static String planHash(Long academyId, Long principalId, String roleAtPlan, String fingerprint,
                                  String toolName, String inputSha256, String previewSha256,
                                  LocalDateTime expiresAt) {
        return sha256Hex(String.join("|", VERSION, String.valueOf(academyId), String.valueOf(principalId),
                roleAtPlan, fingerprint, toolName, inputSha256, previewSha256, expiresAt.toString()));
    }

    /** 타이밍 공격을 피하는 상수 시간 비교 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
