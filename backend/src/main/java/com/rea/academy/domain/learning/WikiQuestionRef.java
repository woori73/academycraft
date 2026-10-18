package com.rea.academy.domain.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.util.Objects;

/**
 * 내신Wiki 문항 참조 값 객체 (6단계: 연동 구조용 스텁).
 * - 숙제 출제 시 내신Wiki `questions` 테이블의 문항을 참조하기 위한 식별자.
 * - 실제 문항 조회·자동채점 연동은 향후 단계에서 {@link WikiIntegrationPort} 구현체로 연결한다.
 * - 내신Wiki 도메인 코드는 수정하지 않고 참조만 한다 (~/workspace/wemade-reorg 읽기 전용).
 *
 * 화면-기능 분리: 순수 값 객체. UI 의존성 없음.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class WikiQuestionRef {

    /** 내신Wiki 문항 ID (questions.id) */
    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** 문항 출처 (예: NAESIN_WIKI). 외부 시스템 구분용. */
    @Column(name = "source", length = 255)
    private String source;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WikiQuestionRef)) return false;
        WikiQuestionRef that = (WikiQuestionRef) o;
        return Objects.equals(questionId, that.questionId)
                && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(questionId, source);
    }
}
