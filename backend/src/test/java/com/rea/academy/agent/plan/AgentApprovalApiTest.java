package com.rea.academy.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentDispatcher;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.domain.admin.ReaAcademy;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.StudentDto;
import com.rea.academy.security.AuthContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 서버 검증형 AI 쓰기 승인 — 보안 HTTP 테스트 (H2 + MockMvc, 실제 커밋).
 * 검증: 계획 생성·조회·승인·취소, 불리언 우회 차단, 단일 실행, 동시 승인, 만료, 권한·학원 변경,
 * 요청·저장 변조, 중복 요청, 감사 로그, 비밀값 비저장.
 */
class AgentApprovalApiTest extends AgentApprovalTestBase {

    @Autowired
    private AgentDispatcher dispatcher;

    // ===================== 1단계: 계획 생성 =====================

    @Test
    @DisplayName("쓰기 요청(원장): 실행되지 않고 서버 미리보기가 있는 PENDING 계획만 생성되며 데이터는 바뀌지 않는다")
    void givenOwner_whenRequestWrite_thenPendingPlanWithServerPreview_andNoDataChange() throws Exception {
        // given / when
        MvcResult r = execute(ownerKey, academyId,
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"홍길동\",\"grade\":\"중2\"}}");

        // then
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("success").asBoolean()).isFalse();
        assertThat(b.get("requiresApproval").asBoolean()).isTrue();
        JsonNode plan = b.get("plan");
        assertThat(plan.get("status").asText()).isEqualTo("PENDING");
        assertThat(plan.get("planId").asText()).hasSize(36);
        assertThat(plan.get("planHash").asText()).hasSize(64);
        assertThat(plan.get("preview").get("changes").get("학생 이름").asText()).isEqualTo("홍길동");
        assertThat(studentCount("홍길동")).isZero();
        assertThat(auditCount(AuditActionType.AGENT_PLAN_CREATED)).isEqualTo(1);
    }

    @Test
    @DisplayName("미리보기는 서버가 입력으로 생성: 요청에 끼워 넣은 preview/explanation 필드는 무시된다")
    void givenModelSuppliedPreviewFields_whenCreatePlan_thenServerPreviewUsed() throws Exception {
        MvcResult r = execute(ownerKey, academyId,
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"진짜이름\"},"
                        + "\"preview\":{\"summary\":\"가짜 미리보기\"},\"explanation\":\"학생 100명을 삭제합니다\"}");

        JsonNode plan = body(r).get("plan");
        String previewText = plan.get("preview").toString();
        assertThat(previewText).contains("진짜이름").doesNotContain("가짜 미리보기").doesNotContain("삭제");
    }

    // ===================== 우회 차단 (writeApproved) =====================

    @Test
    @DisplayName("writeApproved=true: 자연어·구조화·도구별 모두 400 거부되고 데이터는 바뀌지 않는다 (불리언 우회 차단)")
    void givenWriteApprovedTrue_whenAnyWriteRequest_thenRejected400_andNoChange() throws Exception {
        long classId = createClassId(academyId, "월수금반");
        long studentId = createStudentId(academyId, "기존학생");
        String[] bodies = {
                "{\"command\":\"학생 등록 우회학생\",\"writeApproved\":true}",
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"우회학생\"},\"writeApproved\":true}",
                "{\"tool\":\"assignStudentToClass\",\"params\":{\"studentId\":" + studentId + ",\"classId\":" + classId + "},\"writeApproved\":true}",
                "{\"tool\":\"createNoticeDraft\",\"params\":{\"target\":\"ALL\",\"title\":\"우회공지\",\"content\":\"x\"},\"writeApproved\":true}"
        };
        for (String b : bodies) {
            MvcResult r = execute(ownerKey, academyId, b);
            assertThat(r.getResponse().getStatus()).as(b).isEqualTo(400);
            assertThat(body(r).get("code").asText()).isEqualTo("WRITE_APPROVED_NOT_ACCEPTED");
        }
        assertThat(studentCount("우회학생")).isZero();
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).isEmpty();
        assertThat(auditCount(AuditActionType.AGENT_PLAN_REJECTED)).isEqualTo(bodies.length);
    }

    @Test
    @DisplayName("승인처럼 보이는 다른 필드(approved, planId 등)를 보내도 실행되지 않고 계획만 생성된다")
    void givenFakeApprovalFields_whenExecute_thenOnlyPlanCreated() throws Exception {
        MvcResult r = execute(ownerKey, academyId,
                "{\"command\":\"학생 등록 가짜승인\",\"approved\":true,\"approve\":true,\"confirmed\":true,\"planId\":\"x\"}");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).get("plan").get("status").asText()).isEqualTo("PENDING");
        assertThat(studentCount("가짜승인")).isZero();
    }

    @Test
    @DisplayName("승인된 계획이 있어도 같은 명령을 다시 보내면 새 계획이 생길 뿐 실행되지 않는다")
    void givenExecutedPlan_whenSameCommandAgain_thenNewPendingPlan_notExecuted() throws Exception {
        JsonNode first = registerPlan("반복학생");
        assertThat(approve(ownerKey, academyId, first.get("planId").asText(), first.get("planHash").asText())
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(studentCount("반복학생")).isEqualTo(1);

        MvcResult again = execute(ownerKey, academyId, "{\"command\":\"학생 등록 반복학생\"}");

        assertThat(body(again).get("plan").get("status").asText()).isEqualTo("PENDING");
        assertThat(studentCount("반복학생")).isEqualTo(1);
    }

    @Test
    @DisplayName("입력 변조: 스키마에 없는 키(academyId 등)는 거부되고, 다른 학원의 반 ID는 계획 단계에서 막힌다")
    void givenSmuggledParams_whenCreatePlan_thenRejected() throws Exception {
        long otherAcademy = academyId + 5000;
        academyRepository.save(ReaAcademy.builder().id(otherAcademy).name("다른학원").active(true).build());
        long foreignClass = createClassId(otherAcademy, "남의반");
        long foreignStudent = createStudentId(otherAcademy, "남의학생");
        long ownClass = createClassId(academyId, "우리반");

        MvcResult extraKey = execute(ownerKey, academyId,
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"x\",\"academyId\":" + otherAcademy + "}}");
        MvcResult foreignClassReq = execute(ownerKey, academyId,
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"y\",\"classId\":" + foreignClass + "}}");
        MvcResult foreignStudentReq = execute(ownerKey, academyId,
                "{\"tool\":\"assignStudentToClass\",\"params\":{\"studentId\":" + foreignStudent + ",\"classId\":" + ownClass + "}}");

        for (MvcResult r : List.of(extraKey, foreignClassReq, foreignStudentReq)) {
            JsonNode b = body(r);
            assertThat(b.get("success").asBoolean()).isFalse();
            assertThat(b.has("plan") && !b.get("plan").isNull()).isFalse();
            assertThat(b.get("errorCode").asText()).isEqualTo("INVALID_INPUT");
        }
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).isEmpty();
        assertThat(studentCount("x")).isZero();
    }

    @Test
    @DisplayName("command 와 tool 을 함께 보내면 400, 등록되지 않은 도구 이름은 계획 없이 실패한다")
    void givenBothCommandAndTool_orUnknownTool_thenRejected() throws Exception {
        MvcResult both = execute(ownerKey, academyId,
                "{\"command\":\"학생 등록 a\",\"tool\":\"registerStudent\",\"params\":{\"name\":\"b\"}}");
        MvcResult unknown = execute(ownerKey, academyId, "{\"tool\":\"dropAllStudents\",\"params\":{}}");

        assertThat(both.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(unknown).get("success").asBoolean()).isFalse();
        assertThat(body(unknown).has("plan") && !body(unknown).get("plan").isNull()).isFalse();
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).isEmpty();
    }

    // ===================== 2단계: 승인·단일 실행 =====================

    @Test
    @DisplayName("승인: 저장된 계획이 한 번 실행되고, 같은 승인을 다시 보내면 재실행 없이 저장된 결과를 돌려준다")
    void givenPlan_whenApproveTwice_thenExecutedOnce_andReplayWithoutReexecution() throws Exception {
        JsonNode plan = registerPlan("승인학생");
        String id = plan.get("planId").asText();
        String hash = plan.get("planHash").asText();

        MvcResult first = approve(ownerKey, academyId, id, hash);
        MvcResult second = approve(ownerKey, academyId, id, hash);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(first).get("status").asText()).isEqualTo("EXECUTED");
        assertThat(body(first).get("replayed").asBoolean()).isFalse();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(second).get("status").asText()).isEqualTo("EXECUTED");
        assertThat(body(second).get("replayed").asBoolean()).isTrue();
        assertThat(studentCount("승인학생")).isEqualTo(1);
        assertThat(auditCount(AuditActionType.AGENT_PLAN_EXECUTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 승인(8개 요청): 학생은 정확히 1명만 등록되고 실행 응답도 정확히 1건이다")
    void givenPlan_whenConcurrentApprovals_thenExactlyOneExecution() throws Exception {
        JsonNode plan = registerPlan("동시학생");
        String id = plan.get("planId").asText();
        String hash = plan.get("planHash").asText();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return approve(ownerKey, academyId, id, hash);
            }));
        }
        ready.await();
        start.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long freshExecutions = results.stream().filter(r -> {
            try {
                JsonNode b = body(r);
                return r.getResponse().getStatus() == 200 && "EXECUTED".equals(b.path("status").asText())
                        && !b.path("replayed").asBoolean();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).count();
        assertThat(freshExecutions).isEqualTo(1);
        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isIn(200, 409));
        assertThat(studentCount("동시학생")).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("EXECUTED");
        assertThat(auditCount(AuditActionType.AGENT_PLAN_EXECUTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("더블 클릭(승인 직후 즉시 재승인): 학생은 1명이다")
    void givenDoubleClick_whenApproveRepeatedly_thenSingleStudent() throws Exception {
        JsonNode plan = registerPlan("더블학생");
        for (int i = 0; i < 3; i++) {
            approve(ownerKey, academyId, plan.get("planId").asText(), plan.get("planHash").asText());
        }
        assertThat(studentCount("더블학생")).isEqualTo(1);
    }

    @Test
    @DisplayName("만료(10분): 만료된 계획은 410 으로 거부되고 아무것도 저장되지 않으며 상태는 EXPIRED 가 된다")
    void givenExpiredPlan_whenApprove_then410_andNothingExecuted() throws Exception {
        JsonNode plan = registerPlan("만료학생");
        String id = plan.get("planId").asText();
        LocalDateTime expires = LocalDateTime.parse(plan.get("expiresAt").asText());
        assertThat(expires).isAfter(LocalDateTime.now().plusMinutes(9)).isBefore(LocalDateTime.now().plusMinutes(11));
        jdbc.update("update rea_agent_plans set expires_at = ? where plan_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), id);

        MvcResult r = approve(ownerKey, academyId, id, plan.get("planHash").asText());

        assertThat(r.getResponse().getStatus()).isEqualTo(410);
        assertThat(body(r).get("code").asText()).isEqualTo("PLAN_EXPIRED");
        assertThat(studentCount("만료학생")).isZero();
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(auditCount(AuditActionType.AGENT_PLAN_EXPIRED)).isEqualTo(1);
    }

    @Test
    @DisplayName("취소: 취소한 계획은 승인할 수 없고(409) 아무것도 저장되지 않는다")
    void givenCancelledPlan_whenApprove_then409() throws Exception {
        JsonNode plan = registerPlan("취소학생");
        String id = plan.get("planId").asText();

        MvcResult cancelled = cancel(ownerKey, academyId, id);
        MvcResult approved = approve(ownerKey, academyId, id, plan.get("planHash").asText());

        assertThat(body(cancelled).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(approved.getResponse().getStatus()).isEqualTo(409);
        assertThat(studentCount("취소학생")).isZero();
        assertThat(auditCount(AuditActionType.AGENT_PLAN_CANCELLED)).isEqualTo(1);
    }

    // ===================== 변조 =====================

    @Test
    @DisplayName("잘못된 planHash: 403 으로 거부하되 계획은 유지되어, 올바른 해시로는 승인할 수 있다")
    void givenWrongHash_whenApprove_then403_andPlanStaysPending() throws Exception {
        JsonNode plan = registerPlan("해시학생");
        String id = plan.get("planId").asText();

        MvcResult wrong = approve(ownerKey, academyId, id, "0".repeat(64));

        assertThat(wrong.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(wrong).get("code").asText()).isEqualTo("HASH_MISMATCH");
        assertThat(studentCount("해시학생")).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(approve(ownerKey, academyId, id, plan.get("planHash").asText()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("저장된 입력 변조(입력만 / 입력+입력해시): 올바른 해시로 승인해도 무효 처리되고 변조된 값은 실행되지 않는다")
    void givenTamperedStoredInput_whenApprove_thenInvalidated_andNotExecuted() throws Exception {
        JsonNode p1 = registerPlan("정상학생1");
        jdbc.update("update rea_agent_plans set input_json = ? where plan_id = ?",
                "{\"name\":\"변조학생\"}", p1.get("planId").asText());
        MvcResult r1 = approve(ownerKey, academyId, p1.get("planId").asText(), p1.get("planHash").asText());

        JsonNode p2 = registerPlan("정상학생2");
        String forged = "{\"name\":\"변조학생2\"}";
        jdbc.update("update rea_agent_plans set input_json = ?, input_sha256 = ? where plan_id = ?",
                forged, AgentPlanHasher.sha256Hex(forged), p2.get("planId").asText());
        MvcResult r2 = approve(ownerKey, academyId, p2.get("planId").asText(), p2.get("planHash").asText());

        for (MvcResult r : List.of(r1, r2)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(r).get("code").asText()).isEqualTo("PLAN_TAMPERED");
        }
        assertThat(statusOf(p1.get("planId").asText())).isEqualTo("INVALIDATED");
        assertThat(statusOf(p2.get("planId").asText())).isEqualTo("INVALIDATED");
        assertThat(studentCount("변조학생")).isZero();
        assertThat(studentCount("정상학생")).isZero();
        assertThat(auditCount(AuditActionType.AGENT_PLAN_INVALIDATED)).isEqualTo(2);
    }

    @Test
    @DisplayName("대상 변경: 계획 후 반이 종료되면 승인해도 실행되지 않고(409) 계획은 무효가 된다")
    void givenTargetChangedAfterPlan_whenApprove_thenInvalidated409_andNoEnrollment() throws Exception {
        long classId = createClassId(academyId, "곧종료반");
        long studentId = createStudentId(academyId, "배정대상");
        MvcResult created = execute(ownerKey, academyId,
                "{\"tool\":\"assignStudentToClass\",\"params\":{\"studentId\":" + studentId + ",\"classId\":" + classId + "}}");
        JsonNode plan = body(created).get("plan");
        jdbc.update("update rea_classes set active = false where id = ?", classId);

        MvcResult r = approve(ownerKey, academyId, plan.get("planId").asText(), plan.get("planHash").asText());

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r).get("code").asText()).isEqualTo("TARGET_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from rea_class_enrollments where class_id = ?", Integer.class, classId)).isZero();
        assertThat(statusOf(plan.get("planId").asText())).isEqualTo("INVALIDATED");
    }

    // ===================== 권한·학원 =====================

    @Test
    @DisplayName("강사: AI 쓰기 요청은 서버 초안만 보이고 계획이 없으며, 승인·취소 API 는 403, 계획 조회는 404")
    void givenTeacher_whenWrite_thenDraftOnly_andCannotApproveOrSeePlans() throws Exception {
        JsonNode ownerPlan = registerPlan("원장계획학생");

        MvcResult draft = execute(teacherKey, academyId,
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"강사요청\"}}");
        MvcResult approveByTeacher = approve(teacherKey, academyId, ownerPlan.get("planId").asText(), ownerPlan.get("planHash").asText());
        MvcResult cancelByTeacher = cancel(teacherKey, academyId, ownerPlan.get("planId").asText());
        MvcResult viewByTeacher = getPlan(teacherKey, academyId, ownerPlan.get("planId").asText());

        JsonNode b = body(draft);
        assertThat(b.get("errorCode").asText()).isEqualTo("OWNER_ONLY");
        assertThat(b.get("draftPreview").get("changes").get("학생 이름").asText()).isEqualTo("강사요청");
        assertThat(b.has("plan") && !b.get("plan").isNull()).isFalse();
        assertThat(approveByTeacher.getResponse().getStatus()).isEqualTo(403);
        assertThat(cancelByTeacher.getResponse().getStatus()).isEqualTo(403);
        assertThat(viewByTeacher.getResponse().getStatus()).isEqualTo(404);
        assertThat(studentCount("강사요청")).isZero();
        assertThat(studentCount("원장계획학생")).isZero();
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).hasSize(1);
    }

    @Test
    @DisplayName("강사: 읽기 도구(조회)는 기존대로 동작하고 수동 업무 권한(학생 등록 API)은 변경되지 않았다")
    void givenTeacher_whenReadAndManualWrite_thenUnchanged() throws Exception {
        MvcResult read = execute(teacherKey, academyId, "{\"command\":\"오늘 결석 학생\"}");
        MvcResult manual = mvc.perform(post("/api/rea/" + academyId + "/students")
                .header("X-Auth-Key", teacherKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"수동등록\",\"grade\":\"중1\"}")).andReturn();

        assertThat(body(read).get("success").asBoolean()).isTrue();
        assertThat(manual.getResponse().getStatus()).isIn(200, 201);
        assertThat(studentCount("수동등록")).isEqualTo(1);
    }

    @Test
    @DisplayName("학부모: AI 쓰기 요청은 403")
    void givenParent_whenWrite_then403() throws Exception {
        long studentId = createStudentId(academyId, "자녀");
        String parentKey = studentRepository.findById(studentId).orElseThrow().getAuthKey();

        MvcResult r = execute(parentKey, academyId, "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"학부모요청\"}}");

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(studentCount("학부모요청")).isZero();
    }

    @Test
    @DisplayName("다른 학원: 경로 학원 ID 가 다르면 403, 자기 학원 경로로 남의 계획 ID 를 쓰면 404 이고 거부가 감사된다")
    void givenOtherAcademyOwner_whenUsePlanId_thenForbiddenOrNotFound() throws Exception {
        JsonNode plan = registerPlan("격리학생");
        long academyB = academyId + 3000;
        academyRepository.save(ReaAcademy.builder().id(academyB).name("B학원").active(true).build());
        long ownerB = memberService.createMember(com.rea.academy.dto.MemberDto.CreateRequest.builder()
                .academyId(academyB).role(ReaRole.ACADEMY_OWNER).name("B원장").phone("010-0000-0009").build()).getId();
        String keyB = memberRepository.findById(ownerB).orElseThrow().getAuthKey();

        MvcResult wrongPath = approve(keyB, academyId, plan.get("planId").asText(), plan.get("planHash").asText());
        MvcResult ownPath = approve(keyB, academyB, plan.get("planId").asText(), plan.get("planHash").asText());
        MvcResult view = getPlan(keyB, academyB, plan.get("planId").asText());

        assertThat(wrongPath.getResponse().getStatus()).isEqualTo(403);
        assertThat(ownPath.getResponse().getStatus()).isEqualTo(404);
        assertThat(view.getResponse().getStatus()).isEqualTo(404);
        assertThat(studentCount("격리학생")).isZero();
        assertThat(statusOf(plan.get("planId").asText())).isEqualTo("PENDING");
        assertThat(auditRepository.findByAcademyIdAndActionTypeOrderByIdDesc(academyB, AuditActionType.AGENT_PLAN_REJECTED)).isNotEmpty();
    }

    @Test
    @DisplayName("같은 학원의 다른 원장은 남이 만든 계획을 승인(403)하거나 조회(404)할 수 없다")
    void givenAnotherOwnerInSameAcademy_whenApprove_then403() throws Exception {
        JsonNode plan = registerPlan("본인전용");
        // 서비스는 학원당 원장 1명만 만들 수 있으므로, 같은 학원의 강사를 원장으로 승격한 상황(공동 원장)을 DB 로 만든다
        jdbc.update("update rea_members set role = 'ACADEMY_OWNER' where auth_key = ?", teacherKey);
        String otherKey = teacherKey;

        MvcResult approved = approve(otherKey, academyId, plan.get("planId").asText(), plan.get("planHash").asText());
        MvcResult viewed = getPlan(otherKey, academyId, plan.get("planId").asText());

        assertThat(approved.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(approved).get("code").asText()).isEqualTo("NOT_PLAN_OWNER");
        assertThat(viewed.getResponse().getStatus()).isEqualTo(404);
        assertThat(studentCount("본인전용")).isZero();
    }

    @Test
    @DisplayName("권한 변경: 승인 전에 원장이 강사로 바뀌면 HTTP 는 403, 서비스 재검증도 계획을 무효 처리한다")
    void givenRoleChangedToTeacher_whenApprove_thenForbidden_andInvalidated() throws Exception {
        JsonNode plan = registerPlan("권한변경학생");
        String id = plan.get("planId").asText();
        jdbc.update("update rea_members set role = 'TEACHER' where id = ?", ownerId);

        MvcResult http = approve(ownerKey, academyId, id, plan.get("planHash").asText());
        // 인증 계층이 낡은 원장 컨텍스트를 넘기는 상황을 가정한 서비스 직접 호출
        assertThatThrownBy(() -> planService.approve(ownerContext(), id, plan.get("planHash").asText(), "DIRECT"))
                .isInstanceOfSatisfying(AgentPlanException.class, e -> assertThat(e.code()).isEqualTo("MEMBER_CHANGED"));

        assertThat(http.getResponse().getStatus()).isEqualTo(403);
        assertThat(statusOf(id)).isEqualTo("INVALIDATED");
        assertThat(studentCount("권한변경학생")).isZero();
    }

    @Test
    @DisplayName("회원 비활성: 인증이 막히고(401), 서비스 재검증도 계획을 무효 처리한다")
    void givenMemberDeactivated_whenApprove_thenRejected() throws Exception {
        JsonNode plan = registerPlan("비활성학생");
        String id = plan.get("planId").asText();
        jdbc.update("update rea_members set active = false where id = ?", ownerId);

        MvcResult http = approve(ownerKey, academyId, id, plan.get("planHash").asText());
        assertThatThrownBy(() -> planService.approve(ownerContext(), id, plan.get("planHash").asText(), "DIRECT"))
                .isInstanceOfSatisfying(AgentPlanException.class, e -> assertThat(e.code()).isEqualTo("MEMBER_CHANGED"));

        assertThat(http.getResponse().getStatus()).isEqualTo(401);
        assertThat(studentCount("비활성학생")).isZero();
    }

    @Test
    @DisplayName("학원 비활성: 승인 시 무효 처리되고 실행되지 않는다")
    void givenAcademyDeactivated_whenApprove_thenInvalidated() throws Exception {
        JsonNode plan = registerPlan("학원비활성학생");
        String id = plan.get("planId").asText();
        jdbc.update("update rea_academies set active = false where id = ?", academyId);

        assertThatThrownBy(() -> planService.approve(ownerContext(), id, plan.get("planHash").asText(), "DIRECT"))
                .isInstanceOfSatisfying(AgentPlanException.class, e -> assertThat(e.code()).isEqualTo("ACADEMY_INACTIVE"));

        assertThat(statusOf(id)).isEqualTo("INVALIDATED");
        assertThat(studentCount("학원비활성학생")).isZero();
    }

    @Test
    @DisplayName("연결 변경: 계획 때와 인증 경로(BuildIt 역할)가 달라지면 승인할 수 없다")
    void givenAuthSourceChanged_whenApprove_thenInvalidated() throws Exception {
        MvcResult created = execute(ownerKey, academyId, "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"연결변경\"}}");
        JsonNode plan = body(created).get("plan");
        String id = plan.get("planId").asText();

        assertThatThrownBy(() -> planService.approve(ownerContext(), id, plan.get("planHash").asText(), "BUILDIT:TUTOR"))
                .isInstanceOfSatisfying(AgentPlanException.class, e -> assertThat(e.code()).isEqualTo("CONNECTION_CHANGED"));

        assertThat(statusOf(id)).isEqualTo("INVALIDATED");
        assertThat(studentCount("연결변경")).isZero();
    }

    // ===================== 중복 요청 =====================

    @Test
    @DisplayName("idempotencyKey: 같은 키·같은 요청은 같은 계획을 돌려주고, 같은 키로 다른 요청은 409")
    void givenIdempotencyKey_whenRepeated_thenSamePlan_andConflictOnDifferentInput() throws Exception {
        String key = UUID.randomUUID().toString();
        String body1 = "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"멱등학생\"},\"idempotencyKey\":\"" + key + "\"}";

        JsonNode first = body(execute(ownerKey, academyId, body1)).get("plan");
        JsonNode second = body(execute(ownerKey, academyId, body1)).get("plan");
        MvcResult different = execute(ownerKey, academyId,
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"다른학생\"},\"idempotencyKey\":\"" + key + "\"}");

        assertThat(second.get("planId").asText()).isEqualTo(first.get("planId").asText());
        assertThat(second.get("replayed").asBoolean()).isTrue();
        assertThat(different.getResponse().getStatus()).isEqualTo(409);
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).hasSize(1);
    }

    @Test
    @DisplayName("idempotencyKey 동시 생성(6개 요청): 계획은 정확히 1개만 저장된다")
    void givenSameKeyConcurrently_whenCreate_thenSinglePlanRow() throws Exception {
        String key = UUID.randomUUID().toString();
        String req = "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"경합학생\"},\"idempotencyKey\":\"" + key + "\"}";
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                return execute(ownerKey, academyId, req);
            }));
        }
        start.countDown();
        List<MvcResult> rs = new ArrayList<>();
        for (Future<MvcResult> f : fs) {
            rs.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(rs).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isIn(200, 409));
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).hasSize(1);
        assertThat(studentCount("경합학생")).isZero();
    }

    @Test
    @DisplayName("idempotencyKey 형식이 UUID 가 아니면 계획 없이 거부된다")
    void givenInvalidIdempotencyKey_whenCreate_thenRejected() throws Exception {
        MvcResult r = execute(ownerKey, academyId,
                "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"키오류\"},\"idempotencyKey\":\"not-a-uuid\"}");

        assertThat(body(r).get("success").asBoolean()).isFalse();
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).isEmpty();
    }

    // ===================== 입력 형식 =====================

    @Test
    @DisplayName("승인 요청 형식: planHash 누락 400, planId 형식 오류 400")
    void givenMalformedApproveRequest_thenBadRequest() throws Exception {
        JsonNode plan = registerPlan("형식학생");

        MvcResult noHash = mvc.perform(post("/api/rea/" + academyId + "/agent/plans/" + plan.get("planId").asText() + "/approve")
                .header("X-Auth-Key", ownerKey).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        MvcResult badId = approve(ownerKey, academyId, "not-a-uuid", plan.get("planHash").asText());

        assertThat(noHash.getResponse().getStatus()).isEqualTo(400);
        assertThat(badId.getResponse().getStatus()).isEqualTo(400);
        assertThat(studentCount("형식학생")).isZero();
    }

    @Test
    @DisplayName("승인 본문에 입력을 끼워 넣어도 실행 입력은 저장된 값뿐이다 (본문의 name/params 는 무시)")
    void givenApproveBodyWithExtraInput_whenApprove_thenStoredInputExecuted() throws Exception {
        JsonNode plan = registerPlan("저장된이름");

        mvc.perform(post("/api/rea/" + academyId + "/agent/plans/" + plan.get("planId").asText() + "/approve")
                .header("X-Auth-Key", ownerKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planHash\":\"" + plan.get("planHash").asText() + "\",\"name\":\"바꿔치기\",\"params\":{\"name\":\"바꿔치기\"},\"tool\":\"createNoticeDraft\"}"))
                .andReturn();

        assertThat(studentCount("저장된이름")).isEqualTo(1);
        assertThat(studentCount("바꿔치기")).isZero();
    }

    // ===================== 상태·비밀값·감사 =====================

    @Test
    @DisplayName("결과의 학생 인증키는 승인 직후 응답에만 보이고 저장·이후 조회에는 남지 않는다")
    void givenRegisterResult_thenAuthKeyOnlyInFirstResponse() throws Exception {
        JsonNode plan = registerPlan("키학생");
        String id = plan.get("planId").asText();

        JsonNode first = body(approve(ownerKey, academyId, id, plan.get("planHash").asText()));
        JsonNode later = body(getPlan(ownerKey, academyId, id));

        assertThat(first.get("result").get("authKey").asText()).isNotBlank();
        assertThat(later.get("result").has("authKey")).isFalse();
        assertThat(planRepository.findById(id).orElseThrow().getResultJson()).doesNotContain("authKey");
    }

    @Test
    @DisplayName("감사 로그: 생성·승인·실행이 모두 남고 입력 원문(이름·전화번호)은 들어 있지 않다")
    void givenFullFlow_thenAuditTrailWithoutPii() throws Exception {
        JsonNode plan = registerPlan("개인정보학생");
        approve(ownerKey, academyId, plan.get("planId").asText(), plan.get("planHash").asText());

        assertThat(auditCount(AuditActionType.AGENT_PLAN_CREATED)).isEqualTo(1);
        assertThat(auditCount(AuditActionType.AGENT_PLAN_APPROVED)).isEqualTo(1);
        assertThat(auditCount(AuditActionType.AGENT_PLAN_EXECUTED)).isEqualTo(1);
        // 이 기능이 남긴 AI 계획 감사 로그에만 해당한다 (기존 학생 등록 AOP 감사는 별도)
        String all = auditRepository.findByAcademyIdOrderByIdDesc(academyId).stream()
                .filter(l -> l.getActionType().name().startsWith("AGENT_PLAN_"))
                .map(l -> l.getDetail() + "|" + l.getTargetName() + "|" + l.getActorName())
                .reduce("", String::concat);
        assertThat(all).isNotBlank().doesNotContain("010-1234-5678").doesNotContain("개인정보학생");
        assertThat(auditRepository.findByAcademyIdAndActionTypeOrderByIdDesc(academyId, AuditActionType.AGENT_PLAN_EXECUTED).get(0).getDetail())
                .contains(plan.get("planId").asText());
    }

    @Test
    @DisplayName("실행 중 중단(EXECUTING 장기 체류): 조회 시 결과 불명 실패로 표시하고 재실행하지 않는다")
    void givenStaleExecuting_whenGet_thenFailedUnknown_andNeverReexecuted() throws Exception {
        JsonNode plan = registerPlan("중단학생");
        String id = plan.get("planId").asText();
        jdbc.update("update rea_agent_plans set status = 'EXECUTING', approved_at = ?, approved_by = ? where plan_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(30)), ownerId, id);

        JsonNode view = body(getPlan(ownerKey, academyId, id));
        MvcResult retry = approve(ownerKey, academyId, id, plan.get("planHash").asText());

        assertThat(view.get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("failureCode").asText()).isEqualTo("UNKNOWN_OUTCOME");
        assertThat(retry.getResponse().getStatus()).isEqualTo(409);
        assertThat(studentCount("중단학생")).isZero();
    }

    // ===================== 회귀 =====================

    @Test
    @DisplayName("읽기 도구와 도구 목록은 기존대로 동작한다 (읽기 즉시 실행, 도구 8개 중 쓰기 3개)")
    void givenReadToolsAndToolList_thenUnchanged() throws Exception {
        MvcResult read = execute(ownerKey, academyId, "{\"tool\":\"getStudents\",\"params\":{}}");
        MvcResult tools = mvc.perform(get("/api/rea/" + academyId + "/agent/tools").header("X-Auth-Key", ownerKey)).andReturn();

        assertThat(body(read).get("success").asBoolean()).isTrue();
        JsonNode list = body(tools);
        assertThat(list).hasSize(8);
        long writes = 0;
        for (JsonNode t : list) {
            if (t.get("write").asBoolean()) {
                writes++;
            }
        }
        assertThat(writes).isEqualTo(3);
    }

    @Test
    @DisplayName("모든 쓰기 도구는 preview 를 구현한다 (구현하지 않으면 계획을 만들 수 없는 fail-closed)")
    void givenWriteTools_thenAllOverridePreview() throws Exception {
        for (AgentTool tool : dispatcher.listTools()) {
            if (tool.isWrite()) {
                assertThat(tool.getClass().getMethod("preview", Map.class, AgentContext.class).getDeclaringClass())
                        .as(tool.getName()).isNotEqualTo(AgentTool.class);
            }
        }
    }

    @Test
    @DisplayName("계획 생성 API 직접 호출: 읽기 도구를 계획으로 만들 수 없다")
    void givenReadTool_whenCreatePlanDirectly_thenRejected() {
        AgentTool read = dispatcher.resolve("getStudents");

        assertThatThrownBy(() -> planService.createPlan(ownerContext(), read, Map.of(), null, "STRUCTURED", "DIRECT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).isEmpty();
    }

    @Test
    @DisplayName("학부모(STUDENT 주체) 컨텍스트로 계획 생성/승인을 호출해도 거부된다")
    void givenStudentPrincipal_whenServiceCalled_thenRejected() {
        AgentContext parent = new AgentContext(AuthContext.PrincipalType.STUDENT, academyId, ReaRole.PARENT, 1L, "p", false);
        AgentTool tool = dispatcher.resolve("registerStudent");

        assertThatThrownBy(() -> planService.createPlan(parent, tool, Map.of("name", "x"), null, "STRUCTURED", "DIRECT"))
                .isInstanceOfSatisfying(AgentPlanException.class, e -> assertThat(e.code()).isEqualTo("OWNER_ONLY"));
    }
}
