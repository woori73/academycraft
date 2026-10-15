package com.rea.academy.service;

import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.domain.clazz.ReaClassRepository;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.domain.notice.ReaNoticeRepository;
import com.rea.academy.dto.AttendanceDto;
import com.rea.academy.dto.ClassDto;
import com.rea.academy.dto.NoticeDto;
import com.rea.academy.dto.StudentDto;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 변경 이력(감사 로그) AOP 기록기 (8단계).
 *
 * 기존 서비스(1차 MemberService, 1단계 ClassService, 2단계 AttendanceService,
 * 4단계 NoticeService)의 코드를 수정하지 않고, 메서드 호출 성공 시점에
 * 감사 로그를 남긴다.
 *
 * - @AfterReturning: 반환값에서 대상 정보를 추출 (등록·생성·정정)
 * - @Around: 삭제·종료 계열은 실행 전 대상 이름을 조회한 뒤 기록
 * - 감사 기록 실패는 업무 로직에 영향을 주지 않는다 (실패 격리: warn 로그만)
 * - 행위자(actor)는 기존 서비스 시그니처에 없으므로 null(시스템)로 기록된다
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditRecorder auditRecorder;
    private final ReaStudentRepository studentRepository;
    private final ReaClassRepository classRepository;
    private final ReaNoticeRepository noticeRepository;

    // ================= 학생 등록·삭제 (1차 MemberService) =================

    @AfterReturning(
            pointcut = "execution(* com.rea.academy.service.MemberService.createStudent(..))",
            returning = "res")
    public void afterStudentCreated(JoinPoint jp, Object res) {
        safeRecord(() -> {
            // createStudent(StudentDto.CreateRequest): 학원 ID는 요청 DTO에서 추출
            StudentDto.CreateRequest req = (StudentDto.CreateRequest) jp.getArgs()[0];
            StudentDto.DetailResponse r = (StudentDto.DetailResponse) res;
            auditRecorder.record(
                    req.getAcademyId(),
                    AuditActionType.STUDENT_CREATED, "학생",
                    r.getId(), r.getName(),
                    null, null,
                    "학생 등록: " + r.getName()
                            + (r.getGrade() != null ? " (" + r.getGrade() + ")" : ""));
        });
    }

    @Around("execution(* com.rea.academy.service.MemberService.deactivateStudent(..))")
    public Object aroundStudentDeleted(ProceedingJoinPoint pjp) throws Throwable {
        Long academyId = (Long) pjp.getArgs()[0];
        Long studentId = (Long) pjp.getArgs()[1];
        String name = studentRepository.findById(studentId)
                .filter(s -> academyId.equals(s.getAcademyId()))
                .map(s -> s.getName())
                .orElse(null);
        Object result = pjp.proceed();
        safeRecord(() -> auditRecorder.record(
                academyId,
                AuditActionType.STUDENT_DELETED, "학생",
                studentId, name,
                null, null,
                "학생 삭제(비활성): " + (name != null ? name : "id=" + studentId)));
        return result;
    }

    // ================= 반 생성·종료 (1단계 ClassService) =================

    @AfterReturning(
            pointcut = "execution(* com.rea.academy.service.ClassService.createClass(..))",
            returning = "res")
    public void afterClassCreated(JoinPoint jp, Object res) {
        safeRecord(() -> {
            ClassDto.DetailResponse r = (ClassDto.DetailResponse) res;
            auditRecorder.record(
                    (Long) jp.getArgs()[0],
                    AuditActionType.CLASS_CREATED, "반",
                    r.getId(), r.getName(),
                    null, null,
                    "반 생성: " + r.getName()
                            + (r.getTeacherName() != null ? " (담당: " + r.getTeacherName() + ")" : ""));
        });
    }

    @Around("execution(* com.rea.academy.service.ClassService.closeClass(..))")
    public Object aroundClassClosed(ProceedingJoinPoint pjp) throws Throwable {
        Long academyId = (Long) pjp.getArgs()[0];
        Long classId = (Long) pjp.getArgs()[1];
        String name = classRepository.findById(classId)
                .filter(c -> academyId.equals(c.getAcademyId()))
                .map(c -> c.getName())
                .orElse(null);
        Object result = pjp.proceed();
        safeRecord(() -> auditRecorder.record(
                academyId,
                AuditActionType.CLASS_CLOSED, "반",
                classId, name,
                null, null,
                "반 종료: " + (name != null ? name : "id=" + classId)));
        return result;
    }

    // ================= 출결 정정 (2단계 AttendanceService) =================

    @AfterReturning(
            pointcut = "execution(* com.rea.academy.service.AttendanceService.correctEvent(..))",
            returning = "res")
    public void afterAttendanceCorrected(JoinPoint jp, Object res) {
        safeRecord(() -> {
            AttendanceDto.EventResponse r = (AttendanceDto.EventResponse) res;
            auditRecorder.record(
                    (Long) jp.getArgs()[0],
                    AuditActionType.ATTENDANCE_CORRECTED, "출결",
                    r.getId(), r.getStudentName(),
                    null, null,
                    "출결 정정: " + r.getStudentName() + " → " + r.getEventType());
        });
    }

    // ================= 공지 작성·삭제 (4단계 NoticeService) =================

    @AfterReturning(
            pointcut = "execution(* com.rea.academy.service.NoticeService.create(..))",
            returning = "res")
    public void afterNoticeCreated(JoinPoint jp, Object res) {
        safeRecord(() -> {
            NoticeDto.CreateResponse r = (NoticeDto.CreateResponse) res;
            NoticeDto.DetailResponse n = r.getNotice();
            auditRecorder.record(
                    (Long) jp.getArgs()[0],
                    AuditActionType.NOTICE_CREATED, "공지",
                    n.getId(), n.getTitle(),
                    null, null,
                    "공지 작성: " + n.getTitle()
                            + (n.getClassName() != null ? " (" + n.getClassName() + ")" : " (전체공지)"));
        });
    }

    @Around("execution(* com.rea.academy.service.NoticeService.delete(..))")
    public Object aroundNoticeDeleted(ProceedingJoinPoint pjp) throws Throwable {
        Long academyId = (Long) pjp.getArgs()[0];
        Long noticeId = (Long) pjp.getArgs()[1];
        String title = noticeRepository.findById(noticeId)
                .filter(n -> academyId.equals(n.getAcademyId()))
                .map(n -> n.getTitle())
                .orElse(null);
        Object result = pjp.proceed();
        safeRecord(() -> auditRecorder.record(
                academyId,
                AuditActionType.NOTICE_DELETED, "공지",
                noticeId, title,
                null, null,
                "공지 삭제(비활성): " + (title != null ? title : "id=" + noticeId)));
        return result;
    }

    // ================= 실패 격리 =================

    /** 감사 기록 실패가 업무 로직을 깨지 않도록 격리한다. */
    private void safeRecord(Runnable recording) {
        try {
            recording.run();
        } catch (Exception e) {
            log.warn("감사 로그 기록 실패 (업무 로직은 정상 처리됨): {}", e.getMessage());
        }
    }
}
