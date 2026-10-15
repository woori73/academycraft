-- ============================================================================
-- REA 학원관리 V2: 정식 로그인(JWT) 자격 컬럼 추가
-- ============================================================================
-- 상태: 초안 (DRAFT). 운영 DB에 절대 적용하지 말 것 (운영 적용 전 별도 승인).
-- - rea_members에 로그인 자격·잠금·재설정·리프레시 토큰 컬럼 추가 (전부 NULL 허용)
-- - 기존 데이터 보존: 인증키 회원은 login_id/password_hash가 NULL인 채로 유지
--   → 원장이 PUT /members/{id}/credentials 로 최초 자격 발급
-- - 되돌릴 수 없는 변경 없음 (ADD COLUMN만)
-- ============================================================================

SET NAMES utf8mb4;
SET time_zone = '+09:00';

ALTER TABLE rea_members
    ADD COLUMN login_id                VARCHAR(50)  NULL COMMENT '로그인 아이디 (전역 유니크)',
    ADD COLUMN password_hash           VARCHAR(100) NULL COMMENT 'BCrypt 해시 (평문 저장 금지)',
    ADD COLUMN failed_login_attempts   INT          NOT NULL DEFAULT 0 COMMENT '연속 로그인 실패 횟수',
    ADD COLUMN locked_until            DATETIME(6)  NULL COMMENT '계정 잠금 해제 시각',
    ADD COLUMN password_reset_token    VARCHAR(64)  NULL COMMENT '비밀번호 재설정 토큰',
    ADD COLUMN password_reset_expires_at DATETIME(6) NULL COMMENT '재설정 토큰 만료 시각',
    ADD COLUMN refresh_token_hash      VARCHAR(64)  NULL COMMENT '리프레시 토큰 SHA-256 (로테이션 검증)',
    ADD COLUMN linked_student_id       BIGINT       NULL COMMENT '학부모 계정의 자녀 학생 ID (rea_students.id, 논리 FK)';

ALTER TABLE rea_members
    ADD UNIQUE KEY uk_members_login_id (login_id);
