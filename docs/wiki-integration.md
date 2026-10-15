# REA ↔ 내신Wiki 연동 설계 (설계만, 구현 없음)

> 작성일: 2026-10-13 (5단계 작업 5)
> 단계 성격: **설계 전용**. 코드 수정 없음. `~/workspace/wemade-reorg/`는 읽기 전용 참조,
> 내신Wiki 도메인 코드는 절대 수정하지 않는다 (참조만). 운영 DB 관련 내용 없음.

---

## 1. 개요

REA(학원 운영 관리)는 내신Wiki(문항·정답·해설·오답노트 원천)와 **읽기 전용 단방향**으로만 연동한다.
방향은 **REA가 Wiki를 pull(참조)** 하는 것이며, REA가 Wiki 테이블에 쓰지 않는다.
같은 데이터를 두 DB에 중복 저장하지 않는다 (어느 쪽이 원천인지 명시 — `docs/buildit-integration.md` §2 경계 원칙과 동일).

| 데이터 | 원천 (Source of Truth) | REA의 사용 방식 |
|---|---|---|
| 문항 텍스트·정답·해설 (`questions`) | 내신Wiki | `WikiQuestionRef`(ID 참조)로 참조. 복사하지 않음 |
| 오답 기록 (`wrong_note_entries`) | 내신Wiki | 학습 리포트 조회 시점에 참조 조인 |
| 학교·학년·시험범위 (`question_wikis`) | 내신Wiki | 성적 분석 근거로 참조 |
| 학원 일상 성적 (쪽지시험·수행평가 등) | **REA** (`grades`) | REA에 입력·보관 |
| 숙제 부여·검사·미제출 추적 | **REA** (`homework`) | REA 전담. Wiki 문항은 참조만 |

---

## 2. 기존 포트·스텁 위치와 책임

### 2.1 `WikiIntegrationPort` (인터페이스)

- 위치: `backend/src/main/java/com/rea/academy/domain/learning/WikiIntegrationPort.java`
- 책임 (Javadoc 기준):
  1. `WikiQuestionRef`로 내신Wiki `questions` 문항의 **문항 텍스트·정답·해설 조회**
  2. `wrong_note_entries`를 학습 리포트의 **오답 근거**로 조회
  3. `question_wikis`의 **학교·학년·시험범위** 데이터를 성적 분석에 활용
- 현재 API: `Optional<WikiQuestionView> resolve(WikiQuestionRef ref)`
  (`WikiQuestionView`: 문항 상세 보기용 값 객체 — `questionId`, `source`, `questionText`)

### 2.2 `StubWikiIntegrationPort` (스텁 구현체)

- 위치: `backend/src/main/java/com/rea/academy/domain/learning/StubWikiIntegrationPort.java`
- 책임: 미연동 상태에서 `resolve()` 호출 시 **빈 값(`Optional.empty()`) 반환**.
  실제 구현체로 교체될 때까지 REA는 "문항 참조만 저장하고 내용은 해석하지 않는" 상태로 동작한다.

### 2.3 `WikiQuestionRef` (참조 값 객체)

- 위치: `backend/src/main/java/com/rea/academy/domain/learning/WikiQuestionRef.java`
- 책임: REA 숙제(`ReaHomework`)가 Wiki 문항을 "복사"하지 않고 **ID로 참조**하는 값 객체.
  Wiki 문항이 수정되면 REA 숙제에도 최신 문항이 반영되는 구조가 기본이다.
  (시험 출제 후 문항 고정이 필요한 경우 — 스냅샷 정책 — 는 별도 정책으로 스냅샷 테이블을 둔다.
  `docs/buildit-integration.md` §6 미결정 사항 4)

### 2.4 교체 지점

`StubWikiIntegrationPort` → 실제 구현체 교체 시점에만 `WikiIntegrationPort`의 구현체를 하나 추가하면 된다.
호출 측(숙제 등록·리포트 조회)은 인터페이스에만 의존하므로 수정이 필요 없다.

---

## 3. 데이터 흐름 (시퀀스)

### 3.1 REA 숙제 등록 → Wiki 문항 조회

```
[학원 관리자/강사]                    [REA]                              [내신Wiki]
  숙제 등록 요청 ──▶ POST /{academyId}/homework
                       {…, wikiQuestionRef: [Q-101, Q-205]}
                     ReaHomework 저장
                       (문항 "복사" 없음, Ref만 저장)
                            │ resolve(ref)
                            ▼
                     WikiIntegrationPort ──(읽기 전용 조회)──▶ questions 테이블
                            │ ◀── WikiQuestionView(문항 텍스트·정답·해설)
                            ▼
                     숙제 안내 화면(HW-03)에 문항 내용 렌더링
```

규칙:
- REA는 `questions` 테이블에 쓰지 않는다.
- 문항이 삭제·변경된 경우 REA 숙제는 "참조 끊김"으로 표시하고, 등록 자체는 유지한다
  (숙제 이력 보존 — 삭제된 문항 때문에 숙제가 사라지지 않음).

### 3.2 Wiki 오답 → REA 학습 리포트

```
[내신Wiki]                            [REA]
wrong_note_entries                  GET /{academyId}/reports/student/{studentId}
  (conversation_id 기준)                    │
       │                           성적(REA grades) + 숙제(REA homework)
       │                           + 진도(progress) + 출결(attendance) 종합
       ▼                                    │
  ┌────────────────┐                        │  오답 근거 조회 (참조 조인)
  │ 매핑 테이블     │  ←─ student_id ↔ conversation_id ─▶ │
  │ (3단계 범위)   │      매핑이 있어야 조회 가능            │
  └────────────────┘                        ▼
                                  리포트에 "오답 문항" 섹션 추가
                                  (문항 링크는 Wiki 화면으로 — REA가 오답 UI를 중복 구현하지 않음)
```

제약 (현재 스텁 상태의 이유):
- `wrong_note_entries`는 `conversation_id` 기준으로 저장되어 REA의 `student_id`와 직접 연결되지 않는다.
  실제 연동 시 `student_id ↔ conversation_id` 매핑 테이블이 필요하며,
  이는 3단계(데이터 동기화) 범위다 (`docs/buildit-integration.md` §3.2 규칙 3).

### 3.3 REA 성적 → (참조만, 쓰기 없음)

- 내신Wiki 시험 결과는 LMS `StudentScore`(`exam_id`·`source_id`로 Wiki 시험과 연결)가 원천이다.
- REA 성적 화면에서 "시험 성적 가져오기"는 **조회 시점 참조 조인**으로 제공하며,
  REA `grades` 테이블에 복사하지 않는다 (중복 저장 금지).

---

## 4. 실제 구현 시 체크리스트 (구현 승인 후)

- [ ] `WikiIntegrationPort` 실제 구현체 작성 (읽기 전용 쿼리만. Wiki 코드 수정 없음)
- [ ] Wiki DB 접근 경로 확정 (읽기 전용 계정·뷰·API 중 택 1 — 쓰기 권한 없는 경로)
- [ ] `student_id ↔ conversation_id` 매핑 테이블 설계 (오답 연동 시)
- [ ] 문항 참조 끊김(삭제·변경) 시 숙제 화면 표시 정책 확정
- [ ] 문항 스냅샷 정책 결정 (시험 출제 후 고정 필요 여부 — 학원 운영 정책)
- [ ] 개인정보: 문항 조회 범위에 학생 식별자가 섞이지 않는지 검증

---

*이 문서는 설계 전용이다. 코드 변경·DB 변경·배포를 포함하지 않는다.*
