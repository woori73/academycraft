package com.rea.academy.domain.learning;

import java.util.Optional;

/**
 * 내신Wiki 연동 포트 (6단계: 인터페이스/스텁만 정의. 실제 연동은 향후).
 *
 * 향후 구현체가 담당할 책임:
 * - {@link WikiQuestionRef}로 내신Wiki `questions` 문항의 문항 텍스트·정답·해설 조회
 * - `wrong_note_entries`를 학습 리포트의 오답 근거로 조회
 * - `question_wikis`의 학교·학년·시험범위 데이터를 성적 분석에 활용
 *
 * 제약: 내신Wiki 도메인 코드는 수정하지 않고 참조만 한다
 * (~/workspace/wemade-reorg 읽기 전용).
 */
public interface WikiIntegrationPort {

    /** 문항 참조를 실제 문항 정보로 해석한다. 미연동 상태에서는 빈 값을 반환한다. */
    Optional<WikiQuestionView> resolve(WikiQuestionRef ref);

    /** 문항 상세 보기용 값 객체 (스텁). */
    record WikiQuestionView(Long questionId, String source, String questionText) {}
}
