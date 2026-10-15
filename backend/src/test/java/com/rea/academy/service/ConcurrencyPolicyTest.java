package com.rea.academy.service;

import com.rea.academy.domain.attendance.ReaAttendanceEvent;
import com.rea.academy.domain.clazz.ReaClass;
import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.notice.ReaNotice;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.MemberDto;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REA 6단계 작업 2 (2-3) — 동시 수정 정책 결정 및 현재 동작 기록.
 *
 * <p><b>정책 결정 (2026-10-13)</b>: 낙관적 락(@Version)을 권장한다. 다만 본 테스트가
 * 기록하는 현재 구현에는 {@code @Version}이 없어 <b>마지막 쓰기 승리(last-write-wins)</b>
 * 로 동작한다. 낙관적 락 도입은 엔티티 스키마 변경(버전 컬럼 추가)이므로,
 * 본 작업에서는 정책 결정 + 현재 동작 테스트 기록까지만 수행하고 코드 변경은 하지 않는다.</p>
 *
 * <p>관련 발견: {@link MemberService#updateStudent}는 {@code UpdateRequest} 값을
 * 엔티티에 반영하지 않는 no-op이며, 학생 정보 수정용 PUT 엔드포인트도 존재하지 않는다
 * (컨트롤러에 미등록). 따라서 학생 정보의 HTTP 레벨 동시 수정은 현재 발생하지 않으며,
 * 본 테스트는 실제로 값을 반영하는 {@code ClassService.updateClass}를 대상으로 한다.</p>
 */
@SpringBootTest
@Transactional
class ConcurrencyPolicyTest {

    /** 스레드 테스트용 격리 학원 (별도 트랜잭션에서 생성된 데이터와 충돌 방지) */
    private static final Long ACADEMY = 901L;

    @Autowired
    private MemberService memberService;
    @Autowired
    private ClassService classService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 워커 스레드(각자 독립 트랜잭션)가 볼 수 있도록 픽스처를 별도 트랜잭션으로 커밋한다.
     * (테스트 메서드의 외부 트랜잭션은 스레드 로컬이라 스레드에서 미커밋 데이터를 볼 수 없음)
     */
    private long[] seedCommittedFixtures(String className) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            long ownerId = seedOwner();
            long classId = seedClass(ownerId, className);
            return new long[]{ownerId, classId};
        });
    }

    private long seedOwner() {
        return memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(ACADEMY).role(ReaRole.ACADEMY_OWNER)
                .name("원장").phone("010-0000-0001").build()).getId();
    }

    private long seedClass(long ownerId, String name) {
        return classService.createClass(ACADEMY, ClassDto.CreateRequest.builder()
                .academyId(ACADEMY).name(name).teacherId(ownerId).build()).getId();
    }

    private ClassDto.UpdateRequest nameChange(String name) {
        return ClassDto.UpdateRequest.builder().name(name).build();
    }

    // ===== 2-3a: 현재 정책 상태 (낙관적 락 미구현) =====

    @Test
    @DisplayName("현재 상태: 주요 수정 가능 엔티티에 @Version(낙관적 락)이 없다")
    void noOptimisticLocking_configured() {
        for (Class<?> entity : List.of(ReaStudent.class, ReaMember.class,
                ReaClass.class, ReaNotice.class, ReaAttendanceEvent.class)) {
            boolean hasVersion = false;
            for (Field f : entity.getDeclaredFields()) {
                if (f.isAnnotationPresent(Version.class)) {
                    hasVersion = true;
                    break;
                }
            }
            assertThat(hasVersion)
                    .as(entity.getSimpleName() + "에 @Version이 없어야 함 (현재 정책: 미구현)")
                    .isFalse();
        }
    }

    // ===== 2-3b: 현재 동작 — 마지막 쓰기 승리 =====

    @Test
    @DisplayName("2개 세션에서 같은 반을 순차 수정 → 마지막 쓰기 승리 (예외 없음)")
    void twoSessionsSequential_lastWriteWins() {
        long ownerId = seedOwner();
        long classId = seedClass(ownerId, "동시수정반");

        // 세션1·세션2 모두 원래 상태를 기준으로 읽기 (stale read 시뮬레이션)
        ClassDto.DetailResponse session1View = classService.getClassDetail(ACADEMY, classId);
        ClassDto.DetailResponse session2View = classService.getClassDetail(ACADEMY, classId);
        assertThat(session1View.getName()).isEqualTo("동시수정반");
        assertThat(session2View.getName()).isEqualTo("동시수정반");

        // 세션1 수정 → 세션2가 오래된 화면을 기준으로 수정 (충돌 발생해도 예외 없음)
        classService.updateClass(ACADEMY, classId, nameChange("세션1-반이름"));
        classService.updateClass(ACADEMY, classId, nameChange("세션2-반이름"));

        // 마지막 쓰기가 승리한다 (낙관적 락이 없으므로 OptimisticLockException 없음)
        assertThat(classService.getClassDetail(ACADEMY, classId).getName())
                .isEqualTo("세션2-반이름");
    }

    @Test
    @DisplayName("2개 스레드에서 같은 반을 동시 수정 → 예외 없이 하나가 승리")
    void twoThreadsConcurrent_noLockingException() throws Exception {
        long classId = seedCommittedFixtures("스레드반")[1];

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String name : List.of("스레드A-반이름", "스레드B-반이름")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    classService.updateClass(ACADEMY, classId, nameChange(name));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS); // 예외(OptimisticLock 등)가 발생하면 여기서 전파됨
            }
        } finally {
            pool.shutdownNow();
        }

        String finalName = classService.getClassDetail(ACADEMY, classId).getName();
        assertThat(finalName).isIn("스레드A-반이름", "스레드B-반이름");
    }

    @Test
    @DisplayName("부분 업데이트 시 지정하지 않은 capacity는 유지된다 (null=no-change 회귀 방지)")
    void partialUpdate_preservesUnspecifiedCapacity() {
        long ownerId = seedOwner();
        long classId = seedClass(ownerId, "덮어쓰기반");

        // 원장이 정원(capacity)을 30으로 설정
        classService.updateClass(ACADEMY, classId,
                ClassDto.UpdateRequest.builder().capacity(30).build());
        // 다른 세션이 (이름만 바꾸려다) capacity를 읽지 않고 전체를 다시 쓰는 상황을 시뮬레이션:
        // UpdateRequest의 null 필드는 변경 없음이므로 capacity는 유지된다 — 부분 업데이트 안전
        classService.updateClass(ACADEMY, classId, nameChange("이름만변경반"));

        ClassDto.DetailResponse detail = classService.getClassDetail(ACADEMY, classId);
        assertThat(detail.getName()).isEqualTo("이름만변경반");
        assertThat(detail.getCapacity()).isEqualTo(30);
    }
}
