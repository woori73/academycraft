# REA 학원관리 솔루션 — 1~8단계 구현 완료

> 시작일: 2026-10-13
> 기준: 비투(B2) 공개 화면·사용 흐름 조사
> 상태: **8단계 전체 구현 완료** (상세: `docs/FINAL-REPORT.md`)

## 프로젝트 구조

```
rea-academy/
├── docs/
│   └── functional-spec-v1.md    # 화면별 기능 명세서
├── backend/                      # Spring Boot (Java 17)
│   ├── pom.xml
│   └── src/main/java/com/rea/academy/
│       ├── ReaAcademyApplication.java
│       ├── domain/member/        # 회원·학생 엔티티
│       │   ├── ReaRole.java
│       │   ├── ReaMember.java
│       │   ├── ReaStudent.java
│       │   ├── ReaMemberRepository.java
│       │   └── ReaStudentRepository.java
│       ├── dto/                  # MemberDto, StudentDto
│       ├── service/
│       │   └── MemberService.java
│       ├── controller/
│       │   ├── MemberController.java
│       │   └── AuthController.java
│       └── config/
├── frontend/                     # 화면-기능 분리: 순수 UI
│   └── screens/
│       └── MBR-01-student-list.html
```

## 구현 원칙

1. **화면-기능 분리**: Domain/Service는 UI 의존성 없음. UI 교체 시 Service·Domain 그대로 사용.
2. **독립 코드**: 비투의 공개 동작만 참고. 비공개 소스코드·디자인 자산 복제 금지.
3. **인증키 방식**: 회원가입 없이 인증키로 로그인 (비투 방식)
4. **멀티테넌트**: 모든 테이블에 academy_id 포함

## 개발 순서

| 순서 | 기능 | 상태 |
|---|---|---|
| 1차 | 학생·학부모·강사 관리 | ✅ 완료 |
| 1단계 | 반 관리 | ✅ 완료 |
| 2단계 | 출결 | ✅ 완료 |
| 3단계 | 알림 | ✅ 완료 (Mock 발송) |
| 4단계 | 공지 | ✅ 완료 |
| 5단계 | 상담 | ✅ 완료 |
| 6단계 | 성적·숙제 관리 | ✅ 완료 |
| 7단계 | 운영 관리 (일정·통계·CSV) | ✅ 완료 |
| 8단계 | 학원 관리·관리자 기능 (학원 정보·지점·권한·감사 로그) | ✅ 완료 |

- 테스트: 9개 클래스 **164건 전부 PASS** (H2 인메모리)
- 화면: 33개 (`frontend/screens/`), API: 76개
- 최종 보고서: `docs/FINAL-REPORT.md`

## 재사용 코드 (BuildIt)

- P0: User/Role/Student, SecurityConfig, Academy 멀티테넌트
- P1: 수업 커뮤니티 (공지·상담 패턴), FileStorage
- P2: StudentScore, LmsEnrollment 구조 참조
- 신규: 출결, 알림 발송, 반 관리, 인증키 로그인

## 제약

- 운영 DB 변경 금지 (별도 승인 필요)
- 배포 금지 (별도 승인 필요)
- EASY 장부 READ ONLY (연결하지 않음)
- 수납·결제는 결제선생 외부 연결 (LMS 링크만 제공)

## 실행 방법 (개발용)

```bash
cd backend
# Maven 필요 (네트워크에서 다운로드)
mvn spring-boot:run
# H2 인메모리 DB 사용. http://localhost:8081
```

## API 목록 (1차)

```
POST   /api/rea/{academyId}/students          # 학생 등록 (인증키 자동 발급)
GET    /api/rea/{academyId}/students          # 학생 목록
GET    /api/rea/{academyId}/students/{id}     # 학생 상세 (인증키 포함)
DELETE /api/rea/{academyId}/students/{id}     # 학생 삭제
POST   /api/rea/{academyId}/students/{id}/renew-key  # 인증키 재발급
POST   /api/rea/{academyId}/members           # 원장·강사 등록
GET    /api/rea/{academyId}/members           # 회원 목록
POST   /api/rea/auth/key-login                # 인증키 로그인
```
