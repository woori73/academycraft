# 비활성 학생 기록 보존·조회 정책

> 확정일: 2026-10-16
> 상태: 구현 완료

## 정책

1. **데이터 보존**: 학생을 비활성화해도 데이터는 삭제되지 않는다 (soft delete).
   - 출결 기록, 성적, 숙제, 상담 이력 모두 보존됨.

2. **일반 목록 제외**: 활성 학생 목록(`GET /students`)에서는 비활성 학생이 제외된다.

3. **원장 조회 가능**: 원장은 비활성 학생 목록을 명시적으로 조회할 수 있다.
   - `GET /api/rea/{academyId}/students/inactive`
   - 기록 보존·감사 목적으로만 사용.

4. **이력 조회**: 비활성 학생의 출결·성적·숙제 이력은 비활성화 후에도 조회 가능하다.

5. **재활성화**: 별도 정책 결정 전까지 비활성 학생의 재활성화는 지원하지 않는다.
   (필요시 원장이 새 학생으로 재등록)

## 구현

- `ReaStudentRepository.findByAcademyIdAndActiveFalse()`
- `MemberService.getInactiveStudents()`
- `MemberController.GET /students/inactive`
