# REA 학원관리 — 모바일 대응 점검 리포트

> 작성일: 2026-10-13 (8단계)
> 대상: `frontend/screens/` 전체 33개 화면 (기존 30개 + 8단계 ADM 3개)
> 방법: 정적 점검 — `viewport` 메타 태그 유무, `@media` 쿼리 수, 유동 레이아웃 요소(flex-wrap·auto-fit 그리드·1열 전환)
> 기준: 코드는 이미 반응형으로 작성됨. 본 문서는 점검 결과의 문서화이며 코드 수정 없음.

## 1. 점검 결과 요약

| 항목 | 결과 |
|---|---|
| viewport 메타 태그 | **33/33 보유** (누락 0) |
| `@media` 명시 분기 | 22/33 보유 |
| `@media` 없이 유동 레이아웃만으로 대응 | 11/33 (flex-wrap·auto-fit 그리드 등) |
| 인라인 JS 문법 검사 (`node --check`) | **33/33 통과** |

## 2. 화면별 점검표

| 화면 | viewport | @media | 모바일 대응 방식 | 비고 |
|---|---|---|---|---|
| ADM-01-academy-info.html | O | 1 | 600px 이하 1열 전환·버튼 전체폭 | 8단계 신규 |
| ADM-02-user-roles.html | O | 1 | 1열 전환·테이블 열 숨김(연락처) | 8단계 신규 |
| ADM-03-audit-log.html | O | 1 | 필터 1열 전환·버튼 전체폭 | 8단계 신규 |
| ATT-01-attendance-keypad.html | O | 0 | 키패드 버튼 고정 크기 (터치 친화) | 숫자 키패드 특성상 유동 배치 |
| ATT-02-attendance-monthly.html | O | 1 | flex-wrap | |
| ATT-03-attendance-roster.html | O | 1 | flex-wrap | |
| ATT-04-attendance-settings.html | O | 0 | 단일 폼 열 (자연 유동) | 폼 위주라 분기 불필요 |
| CLS-01-class-list.html | O | 1 | flex-wrap | |
| CLS-02-class-form.html | O | 0 | flex-wrap | 폼 위주 |
| CLS-03-class-detail.html | O | 1 | flex-wrap | |
| CNS-01-consultation-list.html | O | 1 | flex-wrap | |
| CNS-02-consultation-form.html | O | 0 | 단일 폼 열 (자연 유동) | 폼 위주 |
| CNS-03-inquiry-inbox.html | O | 1 | flex-wrap | |
| CNS-04-news-send.html | O | 1 | flex-wrap | |
| CNS-05-consultation-detail.html | O | 1 | flex-wrap | |
| GRD-01-grade-form.html | O | 1 | — | 폼 위주 |
| GRD-02-grade-list.html | O | 1 | flex-wrap | |
| HW-01-homework-form.html | O | 1 | flex-wrap | |
| HW-02-homework-check.html | O | 0 | flex-wrap | |
| HW-03-homework-list.html | O | 0 | flex-wrap | |
| MBR-01-student-list.html | O | 0 | 카드 목록 자연 유동 | |
| NTC-01-notice-list.html | O | 1 | flex-wrap | |
| NTC-02-class-news.html | O | 1 | flex-wrap | |
| NTC-03-notice-form.html | O | 0 | 단일 폼 열 (자연 유동) | 폼 위주 |
| NTC-04-notice-detail.html | O | 1 | flex-wrap | |
| NTF-01-notification-list.html | O | 1 | flex-wrap | |
| NTF-02-message-send.html | O | 0 | flex-wrap | |
| NTF-03-send-history.html | O | 0 | flex-wrap | |
| OPS-01-schedule-calendar.html | O | 1 | flex-wrap | |
| OPS-02-dashboard.html | O | 0 | auto-fit 그리드 (150px 최소 카드) | 분기 없이 카드 수 자동 조절 |
| OPS-03-excel-io.html | O | 1 | flex-wrap | |
| RPT-01-learning-report.html | O | 2 | flex-wrap | 프린트 대응 포함 |
| timetable-weekly.html | O | 1 | 7열 → 1열 전환 | 명시적 모바일 1열 |

## 3. 판정

- **전체 적합**: 모든 화면이 `width=device-width, initial-scale=1.0` viewport를 보유하고,
  좁은 화면에서 깨지는 고정폭 레이아웃이 없다.
- `@media`가 없는 11개 화면은 폼 위주 단일 열 또는 flex-wrap/auto-fit 유동 레이아웃이라
  별도 분기 없이 모바일에서 정상 동작한다. (코드 수정 불필요 — 점검 문서화로 갈음)
- 향후 고도화 시 검토: ATT-01 키패드의 태블릿 가로모드 최적화,
  테이블형 화면(ADM-02·ATT-03)의 작은 화면 열 숨김 규칙 통일.

## 4. 검증 명령 (재현)

```bash
cd ~/workspace/rea-academy/frontend/screens
# viewport / @media 점검: 본 문서 §2 표 생성 스크립트 (phase8 작업 기록 참조)
# 인라인 JS 문법 검사:
for f in *.html; do
  python3 -c "
import re; html=open('$f',encoding='utf-8').read()
open('/tmp/c.js','w',encoding='utf-8').write('\n'.join(re.findall(r'<script>(.*?)</script>',html,re.S)))"
  node --check /tmp/c.js || echo "FAIL: $f"
done
```
