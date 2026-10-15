# AcademyCraft 인수인계 문서

> 작성일: 2026-10-16
> 프로젝트: `~/workspace/rea-academy/` (AcademyCraft 학원관리 LMS)
> 상태: 기능 개발 종료, 시범 운영 대기

---

## 1. 테스트 실행 결과

| 항목 | 결과 |
|---|---|
| 실행일 | 2026-10-16 01:56 KST |
| 테스트 파일 | 22개 |
| 총 테스트 | 318개 |
| 통과 | **318개** |
| 실패 | 0개 |
| 실행 방식 | `.testbuild/` javac + 커스텀 JUnit 러너 (개별 실행) |

**실행 명령**:
```bash
cd ~/workspace/rea-academy
CP=$(cat .testbuild/cp.txt)
TESTS=$(find backend/src/test -name "*Test.java" | sed 's|backend/src/test/java/||; s|/|.|g; s|\.java||' | tr '\n' ' ')
java -cp "$CP:.testbuild/classes:.testbuild/test-classes:.testbuild/runner" TestRunner $TESTS
```

---

## 2. 수정 파일 목록 (이번 마무리 작업)

### 백엔드 (5개)

| 파일 | 변경 내용 |
|---|---|
| `domain/member/ReaStudent.java` | `updateInfo(name, grade, schoolName)` 메서드 추가 |
| `service/MemberService.java` | `updateStudent()` 실제 구현 (no-op → 필드 업데이트), `getInactiveStudents()` 추가 |
| `controller/MemberController.java` | `PUT /students/{studentId}` 추가, `GET /students/inactive` 추가 |
| `domain/member/ReaStudentRepository.java` | `findByAcademyIdAndActiveFalse()` 추가 |
| `domain/notification/MockNotificationSender.java` | PII 마스킹: 이름 첫 글자만, 전화번호 뒤 4자리만, 제목·본문은 길이만 로그 |

### 프론트엔드

| 파일 | 변경 내용 |
|---|---|
| `screens/PAY-01-payment-coming-soon.html` | **신규** — 수납·결제 "준비 중" 페이지 (결제 기능 없음 명시) |
| `screens/OPS-02-dashboard.html` | AcademyCraft 브랜딩, 전체 기능 메뉴 (학생·반·출결·숙제·공지·상담·AI·수납) |
| `screens/MBR-01-student-list.html` | "상세·수정" 버튼 → PUT API 연결 |
| `screens/*.html` (36개) | "REA 학원관리" → "AcademyCraft" 브랜딩 변경 |
| `screens/*.html` (10개) | 480px 모바일 미디어쿼리 추가 (기존 26개는 이미 적용) |

### 문서 (1개)

| 파일 | 내용 |
|---|---|
| `docs/inactive-student-policy.md` | **신규** — 비활성 학생 기록 보존·조회 정책 |

---

## 3. 검증 구분

### 실제 브라우저에서 확인한 항목
- 없음 (이번 작업에서는 브라우저 E2E 미수행)

### 코드·단위 테스트로만 검증한 항목
- 학생 정보 수정 API (`PUT /students/{id}`) — 단위 테스트 통과, 브라우저 미확인
- PII 로그 마스킹 — 테스트 로그에서 `학********` 마스킹 확인
- 비활성 학생 조회 API (`GET /students/inactive`) — 단위 테스트 통과
- 수납·결제 "준비 중" 페이지 — HTML 정적 확인만
- AcademyCraft 브랜딩 — 문자열 치환 확인만
- 모바일 미디어쿼리 — CSS 존재 확인만, 실제 디바이스 미확인

### 기존 문서의 테스트 기록
- `docs/FINAL-REPORT.md`의 318/318 PASS는 2026-10-13 기준
- 이번에 재실행하여 318/318 PASS 재확인 (2026-10-16)

---

## 4. API 변경 사항

### 신규 API

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| PUT | `/api/rea/{academyId}/students/{studentId}` | 학생 정보 수정 (이름·학년·학교·학부모) | 원장·강사 |
| GET | `/api/rea/{academyId}/students/inactive` | 비활성 학생 목록 (원장 전용) | 원장 |

### 요청/응답 예시

**PUT /students/{id}**:
```json
// 요청
{ "name": "홍길동", "grade": "중2", "schoolName": "압구정중",
  "parentName": "홍부모", "parentPhone": "010-1234-5678" }
// 응답: StudentDto.DetailResponse
```

---

## 5. 수납·결제 상태

- **"준비 중"으로 유지**
- `PAY-01-payment-coming-soon.html`: 메뉴는 표시, 기능은 비활성화
- 결제선생 API 연동 없음
- 카드 결제·자동 청구·결제 요청 구현 없음
- 허위 수납 데이터 표시 없음

---

## 6. 백업 정보

| 백업 | 파일 | SHA256 | 비고 |
|---|---|---|---|
| 1차 (2026-10-16 01:40) | `rea-academy-backup-20261016_014040.tar.gz` | `86b62c38...` | 마무리 작업 전 |
| 2차 (2026-10-16, 예정) | `rea-academy-backup-20261016_*.tar.gz` | 측정 예정 | 마무리 작업 후 |

---

## 7. GitHub 이전 준비

- `.gitignore` 권장 내용: `*.class`, `.testbuild/classes/`, `.testbuild/test-classes/`, `*.log`
- 저장소명 권장: `woori73/academycraft` (Private)
- 현재는 업로드하지 않음 (별도 승인 필요)

---

## 8. 시범 운영 준비 상태

### 준비 완료
- [x] 8단계 전체 기능 구현
- [x] 학생 수정 API
- [x] PII 로그 마스킹
- [x] 비활성 학생 정책
- [x] 테스트 318/318 PASS
- [x] 수납·결제 "준비 중" 처리
- [x] AcademyCraft 브랜딩
- [x] 모바일 대응 (36개 화면)

### 현장 검증 필요 (시범 운영 전)
- [ ] 실제 학원 데이터로 전체 플로우 검증
- [ ] 원장·강사·학부모 역할별 실제 사용 시나리오
- [ ] PC·모바일 실제 디바이스 확인
- [ ] 출결 키패드 실제 동작 확인
- [ ] 알림 발송 (Mock → 실제 연동 시)

### 판단
**시범 운영 조건부 가능** — 코드·테스트 수준에서는 준비 완료.
실제 학원에서의 현장 검증을 거쳐 최종 판단 필요.
