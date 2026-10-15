package com.rea.academy.domain.member;

/**
 * REA 역할 정의.
 * 비투의 인증키 3종(원장/선생님/학생)에 대응.
 * 기존 BuildIt Role enum과 충돌하지 않도록 REA 전용으로 정의.
 */
public enum ReaRole {
    /** 원장 - 학원 전체 관리 */
    ACADEMY_OWNER,
    /** 강사 - 담당 반 학생 관리, 출결·소식·상담 */
    TEACHER,
    /** 학부모 - 자녀 정보 조회 (학생 인증키로 로그인) */
    PARENT
}
