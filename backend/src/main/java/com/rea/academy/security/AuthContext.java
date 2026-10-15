package com.rea.academy.security;

import com.rea.academy.domain.member.ReaRole;

/**
 * 인증키 검증 성공 시 요청 컨텍스트에 저장되는 인증 정보.
 *
 * - 원장/강사 인증키 → principalType=MEMBER, role=원장·강사 본인 역할, principalId=회원 ID
 * - 학생 인증키 → principalType=STUDENT, role=PARENT, principalId=학생 ID
 *
 * 불변 값 객체 (Java Record). 요청 스레드별로 {@link AuthContextHolder}에 보관된다.
 */
public record AuthContext(
        PrincipalType principalType,
        Long principalId,
        Long academyId,
        ReaRole role,
        String name) {

    public enum PrincipalType {
        /** 원장·강사 (ReaMember) */
        MEMBER,
        /** 학생 인증키로 로그인한 학부모 (ReaStudent) */
        STUDENT
    }

    /** 학부모(학생 인증키 로그인) 여부 */
    public boolean isParent() {
        return role == ReaRole.PARENT;
    }

    /** 원장 여부 */
    public boolean isOwner() {
        return role == ReaRole.ACADEMY_OWNER;
    }

    /** 강사 여부 */
    public boolean isTeacher() {
        return role == ReaRole.TEACHER;
    }

    /**
     * 학부모 본인 자녀의 학생 ID.
     * 학부모가 아닌 경우 IllegalStateException.
     */
    public Long ownStudentId() {
        if (!isParent() || principalType != PrincipalType.STUDENT) {
            throw new IllegalStateException("학부모 인증 컨텍스트가 아닙니다.");
        }
        return principalId;
    }
}
