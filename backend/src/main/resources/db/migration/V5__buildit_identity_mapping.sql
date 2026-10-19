-- Explicit external identity mappings; existing academies and credentials are untouched.
CREATE TABLE rea_buildit_academy_links (
 buildit_academy_id BIGINT NOT NULL PRIMARY KEY,
 academy_id BIGINT NOT NULL UNIQUE,
 source_identity_sha256 CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE rea_buildit_member_links (
 buildit_user_id BIGINT NOT NULL,
 buildit_academy_id BIGINT NOT NULL,
 member_id BIGINT NOT NULL UNIQUE,
 buildit_role VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(buildit_user_id,buildit_academy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
