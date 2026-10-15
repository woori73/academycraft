package com.rea.academy.service;

import com.rea.academy.domain.attendance.ReaAttendanceEventRepository;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.OpsDto;
import com.rea.academy.dto.StudentDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REA 6단계 작업 2 (2-4) — 장애 복구 시나리오 검증 (서비스 레벨).
 *
 * <p>① 트랜잭션 롤백: 복수 쓰기 도중 강제 예외 발생 시 부분 데이터가 남지 않음을 확인.
 * ② 일괄 가져오기(CSV)는 의도적으로 행 단위 부분 성공을 허용 (오류 행은 건너뛰고 계속) —
 *    이 또한 명시적 복구 정책이므로 함께 기록한다.
 * ③ 유효하지 않은 출결 기록 시도는 쓰기 전에 검증에서 차단되어 부분 데이터가 생기지 않음.
 * H2 인메모리, 가명 더미 데이터.</p>
 */
@SpringBootTest
@Transactional
class RecoveryTest {

    private static final Long ACADEMY = 1L;

    @Autowired
    private MemberService memberService;
    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private OpsService opsService;
    @Autowired
    private ReaStudentRepository studentRepository;
    @Autowired
    private ReaAttendanceEventRepository eventRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private long seedStudent(String name) {
        return memberService.createStudent(StudentDto.CreateRequest.builder()
                .academyId(ACADEMY).name(name).grade("고2").build()).getId();
    }

    @Test
    @DisplayName("복수 쓰기 도중 강제 예외 → 전체 롤백 (부분 데이터 남지 않음)")
    void unexpectedFailure_rollsBackMultiWrite() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            tx.execute(status -> {
                // 1차 쓰기: 학생 등록
                long studentId = memberService.createStudent(StudentDto.CreateRequest.builder()
                        .academyId(ACADEMY).name("롤백테스트학생").grade("고2").build()).getId();
                // 2차 쓰기: 출결 기록
                attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                        .studentId(studentId).eventType("CHECK_IN").build());
                // 강제 장애 발생
                throw new RuntimeException("강제 예외: 트랜잭션 롤백 검증용");
            });
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).contains("강제 예외");
        }

        // 롤백 확인: 학생도, 출결 이벤트도 남지 않아야 함
        assertThat(studentRepository
                .findByAcademyIdAndNameContainingAndActiveTrue(ACADEMY, "롤백테스트학생"))
                .isEmpty();
    }

    @Test
    @DisplayName("CSV 일괄 가져오기: 오류 행은 건너뛰고 정상 행은 반영 (행 단위 부분 성공 정책)")
    void csvImport_partialSuccessPolicy() {
        String csv = "이름,학년,반,학부모,연락처\n"
                + "가져오기정상,고2,,,\n"
                + ",고1,,,\n"; // 이름 누락 → 실패 행

        OpsDto.StudentImportResponse response = opsService.importStudentsCsv(ACADEMY, csv);

        assertThat(response.getSuccessCount()).isEqualTo(1);
        assertThat(response.getFailedCount()).isEqualTo(1);
        assertThat(response.getErrors()).isNotEmpty();
        // 정상 행은 반영됨 (부분 롤백이 아닌 부분 성공이 명시적 정책)
        assertThat(studentRepository
                .findByAcademyIdAndNameContainingAndActiveTrue(ACADEMY, "가져오기정상"))
                .hasSize(1);
    }

    @Test
    @DisplayName("존재하지 않는 학생 출결 기록 → 검증 단계에서 차단, 부분 데이터 없음")
    void attendanceCheck_invalidStudent_noPartialWrite() {
        assertThatThrownBy(() -> attendanceService.check(ACADEMY,
                AttendanceDto.CheckRequest.builder()
                        .studentId(999999L).eventType("CHECK_IN").build()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(eventRepository
                .findByAcademyIdAndStudentIdOrderByOccurredAtDesc(ACADEMY, 999999L))
                .isEmpty();
    }

    @Test
    @DisplayName("정정 사유 누락 → 검증 단계에서 차단, 원본 이벤트·정정 기록 변경 없음")
    void attendanceCorrect_missingReason_noWrite() {
        long studentId = seedStudent("정정학생");
        Long eventId = attendanceService.check(ACADEMY, AttendanceDto.CheckRequest.builder()
                .studentId(studentId).eventType("CHECK_IN").build()).getId();
        int beforeCount = eventRepository
                .findByAcademyIdAndStudentIdOrderByOccurredAtDesc(ACADEMY, studentId).size();

        assertThatThrownBy(() -> attendanceService.correctEvent(ACADEMY, eventId,
                AttendanceDto.CorrectRequest.builder().reason("").build()))
                .isInstanceOf(IllegalArgumentException.class);

        // 원본 이벤트는 그대로 (타입 변경 없음), 추가 이벤트도 없음
        assertThat(eventRepository
                .findByAcademyIdAndStudentIdOrderByOccurredAtDesc(ACADEMY, studentId))
                .hasSize(beforeCount);
    }
}
