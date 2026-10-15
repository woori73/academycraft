package com.rea.academy.domain.learning;

import java.util.Optional;

/**
 * {@link WikiIntegrationPort}의 스텁 구현 (6단계).
 * 실제 연동 전까지 빈 값을 반환하며, 연동 시 이 클래스를 실제 구현체로 교체한다.
 * Spring Bean으로 등록하지 않는다 — 연동 설계 시점에 주입 방식을 정한다.
 */
public class StubWikiIntegrationPort implements WikiIntegrationPort {

    @Override
    public Optional<WikiQuestionView> resolve(WikiQuestionRef ref) {
        // 미연동: 실제 문항 조회 없이 빈 값 반환
        return Optional.empty();
    }
}
