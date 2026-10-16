-- V3: 회원 이메일 컬럼 추가 (비밀번호 재설정 링크 이메일 발송용)
-- 주의: 운영 DB 적용은 별도 승인 필요 (P0-4)
ALTER TABLE rea_members ADD COLUMN email VARCHAR(100) NULL;
