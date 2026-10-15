# GitHub 이전 준비 (AcademyCraft)

> 상태: 준비만 완료, 업로드하지 않음 (별도 승인 필요)

## 권장 저장소
- 이름: `woori73/academycraft`
- 공개 범위: **Private**
- 설명: AcademyCraft 학원관리 LMS (비투 기반 독립 구현)

## 이전 절차 (승인 후)

```bash
cd ~/workspace/rea-academy
git init
git add -A

# .gitignore 권장 내용:
# *.class
# .testbuild/classes/
# .testbuild/test-classes/
# *.log
# .DS_Store

git commit -m "AcademyCraft 초기 커밋 (8단계 + 마무리 작업)"
gh repo create woori73/academycraft --private --source=. --push
```

## 제외할 파일
- `.testbuild/classes/` — 컴파일 산출물
- `.testbuild/test-classes/` — 테스트 컴파일 산출물
- `*.log` — 로그 파일

## 포함할 파일
- `backend/` 전체 (소스 + 테스트)
- `frontend/` 전체 (35개 화면 + 컴포넌트)
- `db/migration/` (스키마)
- `docs/` 전체 (설계 문서 + 인수인계서)
- `README.md`

## 주의
- 현재는 업로드하지 않음
- 수납·결제 코드는 포함되지 않음 ("준비 중" 페이지만)
- 운영 DB 접속 정보 없음 (H2 기준)
