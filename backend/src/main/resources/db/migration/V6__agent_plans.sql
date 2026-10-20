-- AI 쓰기 승인(P1-a): 서버 보관 실행 계획. 추가 전용 변경(기존 테이블 무수정).
-- 로컬 MySQL 리허설 후에만 운영 적용을 별도 승인으로 요청한다. 운영 적용 전 백업 필수.
-- 롤백: 앱을 되돌리면 이 테이블은 무시된다. 최후 수단은 docs/agent-approval.md 의 복구 절차 참조.
SET NAMES utf8mb4;

CREATE TABLE rea_agent_plans (
    plan_id             VARCHAR(36)     NOT NULL,
    academy_id          BIGINT       NOT NULL,
    principal_id        BIGINT       NOT NULL,
    role_at_plan        VARCHAR(30)  NOT NULL,
    binding_fingerprint VARCHAR(64)     NOT NULL,
    tool_name           VARCHAR(80)  NOT NULL,
    input_json          VARCHAR(4000) NOT NULL,
    input_sha256        VARCHAR(64)     NOT NULL,
    preview_json        VARCHAR(4000) NOT NULL,
    plan_hash           VARCHAR(64)     NOT NULL,
    source              VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    idempotency_key     VARCHAR(36)     NULL,
    created_at          DATETIME(6)  NOT NULL,
    expires_at          DATETIME(6)  NOT NULL,
    approved_at         DATETIME(6)  NULL,
    approved_by         BIGINT       NULL,
    executed_at         DATETIME(6)  NULL,
    result_json         VARCHAR(2000) NULL,
    failure_code        VARCHAR(40)  NULL,
    PRIMARY KEY (plan_id),
    UNIQUE KEY uq_agent_plan_idem (academy_id, idempotency_key),
    KEY idx_agent_plan_academy_status (academy_id, status, expires_at),
    KEY idx_agent_plan_principal (academy_id, principal_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
