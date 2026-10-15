package com.rea.academy.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 도구 이름 → 도구 인스턴스 매핑·실행 (2단계).
 * Spring이 등록한 {@link AgentTool} 빈 목록에서 이름으로 조회한다.
 */
@Component
@RequiredArgsConstructor
public class AgentDispatcher {

    private final List<AgentTool> tools;

    /**
     * 도구 이름으로 인스턴스 조회. 없으면 IllegalArgumentException.
     */
    public AgentTool resolve(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("도구 이름이 비어 있습니다.");
        }
        return tools.stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 도구입니다: " + toolName));
    }

    /** 등록된 모든 도구 목록 */
    public List<AgentTool> listTools() {
        return List.copyOf(tools);
    }

    /** 도구 메타데이터 목록 (이름·설명·스키마·쓰기여부) */
    public List<Map<String, Object>> describeTools() {
        return tools.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("inputSchema", t.getInputSchema());
            m.put("isWrite", t.isWrite());
            return m;
        }).toList();
    }

    /** 도구 존재 여부 */
    public boolean has(String toolName) {
        return tools.stream().anyMatch(t -> t.getName().equals(toolName));
    }
}
