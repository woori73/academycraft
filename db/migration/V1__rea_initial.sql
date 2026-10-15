-- ============================================================================
-- REA 학원관리 시스템 초기 스키마 (Flyway V1, 초안)
-- ============================================================================
-- 상태: **초안 (DRAFT)**. 운영 DB에 절대 적용하지 말 것.
--   - 대상: MySQL 8.x, 문자셋 utf8mb4, collation utf8mb4_unicode_ci
--   - JPA 엔티티(com.rea.academy.domain.*)로부터 수동 추출
--   - 엔티티에 @ManyToOne/JPA FK 제약이 없으므로(전부 논리 Long 컬럼),
--     물리 FK는 생성하지 않고 아래 [논리 FK 목록]을 참고
--   - 타입 매핑: Long→BIGINT, String(n)→VARCHAR(n), TEXT→TEXT,
--     Boolean→TINYINT(1), LocalDateTime→DATETIME(6), LocalDate→DATE,
--     LocalTime→TIME, Integer→INT, ENUM(STRING)→VARCHAR
--   - Hibernate가 직접 생성 시 Boolean→BIT(1), LocalDateTime→DATETIME(6)이므로
--     이 초안을 flyway로 먼저 적용한 뒤 ddl-auto=validate로 정합성을 확인
-- ----------------------------------------------------------------------------
-- 버전 전략:
--   - V1__rea_initial.sql: 전체 초기 스키마 (본 파일)
--   - V2 이후: ALTER TABLE 한 파일당 1개 변경 단위 (예: V2__add_notice_pin.sql)
--   - 되돌릴 수 없는 변경(DROP COLUMN/DROP TABLE)은 V파일에 포함하지 않고
--     별도 승인 후에만 작성 (원칙: 컬럼 추가는 NULL 허용 또는 DEFAULT 포함)
--   - flyway_schema_history로 적용 이력 추적. 운영 적용 전 백업 필수
-- ============================================================================

SET NAMES utf8mb4;
SET time_zone = '+09:00';

-- ============================== 8단계 학원 관리 ==============================

CREATE TABLE rea_academies (
    id               BIGINT       NOT NULL,            -- 학원 ID = 멀티테넌트 키 (자동 생성 없음, 명시 발급)
    name             VARCHAR(100) NOT NULL,
    phone            VARCHAR(20)  NULL,
    address          VARCHAR(200) NULL,
    logo_key         VARCHAR(200) NULL,
    memo             VARCHAR(500) NULL,
    parent_academy_id BIGINT      NULL,                -- 본원 학원 ID (NULL이면 본원/독립)
    active           TINYINT(1)   NOT NULL DEFAULT 1,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_academies_parent (parent_academy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_audit_logs (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    academy_id   BIGINT       NOT NULL,
    action_type  VARCHAR(40)  NOT NULL,               -- ENUM: STUDENT_CREATED, STUDENT_DELETED, CLASS_CREATED, CLASS_CLOSED, ATTENDANCE_CORRECTED, NOTICE_CREATED, NOTICE_DELETED, MEMBER_ROLE_CHANGED, ACADEMY_INFO_UPDATED, BRANCH_CREATED, AGENT_TOOL_EXECUTED
    target_type  VARCHAR(20)  NULL,                    -- 변경 대상 종류: 학생/반/출결/공지/회원/학원
    target_id    BIGINT       NULL,
    target_name  VARCHAR(100) NULL,                   -- 당시 이름 스냅샷
    actor_id     BIGINT       NULL,                    -- 행위자 회원 ID (시스템/미상 시 NULL)
    actor_name   VARCHAR(50)  NULL,
    detail       VARCHAR(2000) NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_logs_academy (academy_id),
    KEY idx_audit_logs_action (academy_id, action_type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 감사 로그는 append-only: 수정·삭제 API 없음. 운영에서 UPDATE/DELETE 금지 (애플리케이션 레벨 강제)

-- ============================== 회원 ==============================

CREATE TABLE rea_members (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    academy_id BIGINT       NOT NULL,
    role       VARCHAR(20)  NOT NULL,                 -- ENUM: ACADEMY_OWNER, TEACHER, PARENT
    name       VARCHAR(50)  NOT NULL,
    auth_key   VARCHAR(32)  NOT NULL,                 -- 인증키 (비투 방식, 회원가입 없음)
    phone      VARCHAR(20)  NULL,
    active     TINYINT(1)   NOT NULL DEFAULT 1,       -- 삭제 시 비활성
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_members_auth_key (auth_key),
    KEY idx_members_academy (academy_id),
    KEY idx_members_academy_role (academy_id, role, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_students (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    academy_id   BIGINT       NOT NULL,
    name         VARCHAR(50)  NOT NULL,
    grade        VARCHAR(20)  NULL,
    school_name  VARCHAR(100) NULL,
    auth_key     VARCHAR(32)  NOT NULL,               -- 학생 인증키 (학부모가 이 키로 로그인)
    parent_name  VARCHAR(50)  NULL,
    parent_phone VARCHAR(20)  NULL,                   -- 알림 수신용
    active       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_students_auth_key (auth_key),
    KEY idx_students_academy (academy_id),
    KEY idx_students_academy_name (academy_id, name, active)  -- 이름 검색(name LIKE %..%) 대응
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================== 반 ==============================

CREATE TABLE rea_classes (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id  BIGINT        NOT NULL,
    name        VARCHAR(50)   NOT NULL,
    description VARCHAR(500)  NULL,
    teacher_id  BIGINT        NOT NULL,               -- rea_members.id (논리 FK)
    weekdays    VARCHAR(30)   NULL,                   -- "MON,WED,FRI" 콤마 구분
    start_time  TIME          NULL,
    end_time    TIME          NULL,
    room        VARCHAR(50)   NULL,
    capacity    INT           NULL,                   -- NULL이면 무제한
    active      TINYINT(1)    NOT NULL DEFAULT 1,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_classes_academy (academy_id),
    KEY idx_classes_academy_active (academy_id, active, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_class_enrollments (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    academy_id  BIGINT       NOT NULL,
    class_id    BIGINT       NOT NULL,                -- rea_classes.id (논리 FK)
    student_id  BIGINT       NOT NULL,                -- rea_students.id (논리 FK)
    enrolled_at DATETIME(6)  NOT NULL,
    active      TINYINT(1)   NOT NULL DEFAULT 1,      -- 제외·이동 시 false (soft delete, 이력 유지)
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_enroll_class (academy_id, class_id, active),
    KEY idx_enroll_student (academy_id, student_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================== 출결 ==============================

CREATE TABLE rea_attendance_events (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id  BIGINT        NOT NULL,
    student_id  BIGINT        NOT NULL,               -- rea_students.id (논리 FK)
    class_id    BIGINT        NULL,                   -- 기록 시점 소속 반 ID 스냅샷
    event_type  VARCHAR(20)   NOT NULL,               -- ENUM: CHECK_IN, CHECK_OUT, ABSENT, LATE, EARLY_LEAVE
    occurred_at DATETIME(6)   NOT NULL,               -- 발생 일시 (기록 대상 일시)
    source      VARCHAR(20)   NOT NULL,               -- ENUM: KEYPAD, MANUAL
    note        VARCHAR(500)  NULL,
    photo_key   VARCHAR(255)  NULL,                  -- 출결 사진 키 (FileStorage 연동용)
    corrected   TINYINT(1)    NOT NULL DEFAULT 0,     -- 정정 이력 존재 여부
    created_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_attend_student (academy_id, student_id, occurred_at),
    KEY idx_attend_range (academy_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_attendance_corrections (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id        BIGINT        NOT NULL,
    event_id          BIGINT        NOT NULL,         -- rea_attendance_events.id (논리 FK)
    before_type       VARCHAR(20)   NOT NULL,
    after_type        VARCHAR(20)   NOT NULL,
    before_occurred_at DATETIME(6)  NOT NULL,
    after_occurred_at  DATETIME(6)  NOT NULL,
    before_note       VARCHAR(500)  NULL,
    after_note        VARCHAR(500)  NULL,
    reason            VARCHAR(500)  NOT NULL,
    corrected_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_attend_corr_event (academy_id, event_id, corrected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_attendance_settings (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id             BIGINT        NOT NULL,
    check_out_earliest_time TIME         NULL,
    check_out_latest_time   TIME         NULL,
    photo_enabled          TINYINT(1)    NULL DEFAULT 0,
    check_in_message       VARCHAR(200)  NULL,
    check_out_message      VARCHAR(200)  NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_setting_academy (academy_id)   -- 학원당 1행
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================== 알림 ==============================

CREATE TABLE rea_notifications (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id           BIGINT        NOT NULL,
    recipient_student_id BIGINT        NOT NULL,      -- rea_students.id (논리 FK)
    recipient_name       VARCHAR(50)   NULL,
    recipient_phone      VARCHAR(20)   NULL,
    channel              VARCHAR(20)   NOT NULL,     -- ENUM: PUSH, SMS, ALIMTALK
    type                 VARCHAR(20)   NOT NULL,     -- ENUM: CHECK_IN, CHECK_OUT, NOTICE, CONSULT_REPLY, GRADE, HOMEWORK
    title                VARCHAR(100)  NOT NULL,
    content              VARCHAR(1000) NOT NULL,
    status               VARCHAR(20)   NOT NULL,     -- ENUM: SENT, FAILED
    fail_reason          VARCHAR(500)  NULL,
    retry_count          INT           NOT NULL DEFAULT 0,
    read_at              DATETIME(6)   NULL,
    created_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notif_student (academy_id, recipient_student_id, created_at),
    KEY idx_notif_unread (academy_id, recipient_student_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_notification_settings (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    academy_id BIGINT       NOT NULL,
    student_id BIGINT       NOT NULL,                -- rea_students.id (논리 FK)
    type       VARCHAR(20)  NOT NULL,                -- ENUM: NotificationType
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    updated_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_setting (academy_id, student_id, type),
    KEY idx_notif_setting_student (academy_id, student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================== 공지 ==============================

CREATE TABLE rea_notices (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id          BIGINT        NOT NULL,
    class_id            BIGINT        NULL,          -- 반 대상 공지 시 (논리 FK)
    title               VARCHAR(200)  NOT NULL,
    content             TEXT          NOT NULL,      -- columnDefinition = TEXT
    author_id           BIGINT        NULL,          -- rea_members.id (논리 FK)
    author_name         VARCHAR(50)   NULL,
    attachment_file_name VARCHAR(255) NULL,
    attachment_file_key  VARCHAR(255) NULL,
    view_count          INT           NOT NULL DEFAULT 0,
    active              TINYINT(1)    NOT NULL DEFAULT 1,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_notices_academy (academy_id, active, id),
    KEY idx_notices_class (academy_id, class_id, active, id),
    FULLTEXT KEY ft_notices_title (title)          -- 제목 검색용 (선택)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_notice_reads (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    academy_id BIGINT       NOT NULL,
    notice_id  BIGINT       NOT NULL,                -- rea_notices.id (논리 FK)
    student_id BIGINT       NOT NULL,                -- rea_students.id (논리 FK)
    read_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notice_read (academy_id, notice_id, student_id),
    KEY idx_notice_reads_notice (academy_id, notice_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================== 상담 ==============================

CREATE TABLE rea_consultations (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id   BIGINT        NOT NULL,
    student_id   BIGINT        NOT NULL,             -- rea_students.id (논리 FK)
    student_name VARCHAR(50)   NULL,                 -- 조회 시점 무관 스냅샷
    type         VARCHAR(20)   NOT NULL,             -- ENUM: INQUIRY(문의), NEWS(아이소식)
    status       VARCHAR(20)   NOT NULL DEFAULT 'OPEN', -- ENUM: OPEN, ANSWERED, CLOSED
    title        VARCHAR(200)  NOT NULL,
    active       TINYINT(1)    NOT NULL DEFAULT 1,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_consult_student (academy_id, student_id, active, id),
    KEY idx_consult_status (academy_id, status, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_consultation_messages (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    academy_id           BIGINT         NOT NULL,
    consultation_id      BIGINT         NOT NULL,   -- rea_consultations.id (논리 FK)
    sender_student_id    BIGINT         NULL,
    sender_member_id     BIGINT         NULL,        -- rea_members.id (논리 FK)
    sender_name          VARCHAR(50)    NULL,
    content              VARCHAR(2000)  NOT NULL,
    attachment_file_name VARCHAR(255)   NULL,
    attachment_file_key  VARCHAR(255)   NULL,
    read_at              DATETIME(6)    NULL,
    created_at           DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_consult_msg_thread (academy_id, consultation_id, id),
    KEY idx_consult_msg_unread (academy_id, consultation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================== 학습 ==============================

CREATE TABLE rea_grades (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id  BIGINT        NOT NULL,
    student_id  BIGINT        NOT NULL,              -- rea_students.id (논리 FK)
    student_name VARCHAR(50)  NULL,                  -- 스냅샷
    exam_name   VARCHAR(100)  NOT NULL,
    subject     VARCHAR(50)   NOT NULL,
    score       INT           NOT NULL,
    full_score  INT           NOT NULL,
    exam_date   DATE          NOT NULL,
    note        VARCHAR(500)  NULL,
    created_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_grades_student (academy_id, student_id, exam_date, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_homework (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id  BIGINT        NOT NULL,
    class_id    BIGINT        NULL,                  -- 반별 숙제 (논리 FK)
    class_name  VARCHAR(100)  NULL,                  -- 스냅샷
    student_id  BIGINT        NULL,                  -- 학생별 숙제 (논리 FK), classId/studentId 중 최소 하나 필수
    student_name VARCHAR(50)  NULL,                  -- 스냅샷
    title       VARCHAR(200)  NOT NULL,
    content     VARCHAR(2000) NOT NULL,
    due_date    DATE          NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_homework_class (academy_id, class_id, due_date, id),
    KEY idx_homework_student (academy_id, student_id, due_date, id),
    KEY idx_homework_academy (academy_id, due_date, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- @ElementCollection (내신Wiki 문항 참조, 연동 스텁)
CREATE TABLE rea_homework_question_refs (
    homework_id BIGINT        NOT NULL,              -- rea_homework.id (논리 FK)
    question_id BIGINT        NULL,
    source      VARCHAR(255)  NULL,
    KEY idx_hw_qref_homework (homework_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_homework_checks (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    academy_id  BIGINT       NOT NULL,
    homework_id BIGINT       NOT NULL,               -- rea_homework.id (논리 FK)
    student_id  BIGINT       NOT NULL,               -- rea_students.id (논리 FK)
    student_name VARCHAR(50) NULL,                   -- 스냅샷
    status      VARCHAR(20)  NOT NULL,               -- ENUM: COMPLETED, INCOMPLETE
    score       INT          NULL,
    comment     VARCHAR(500) NULL,
    checked_at  DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_homework_check (homework_id, student_id),
    KEY idx_hw_check_homework (academy_id, homework_id),
    KEY idx_hw_check_student (academy_id, student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_progress (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id    BIGINT        NOT NULL,
    student_id    BIGINT        NOT NULL,             -- rea_students.id (논리 FK)
    student_name  VARCHAR(50)   NULL,                -- 스냅샷
    unit          VARCHAR(200)  NOT NULL,
    progress_rate INT           NOT NULL,             -- 0~100
    memo          VARCHAR(1000) NULL,
    recorded_date DATE          NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_progress_student (academy_id, student_id, recorded_date, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================== 운영(일정·담당) ==============================

CREATE TABLE rea_schedules (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id         BIGINT        NOT NULL,
    title              VARCHAR(200)  NOT NULL,
    date               DATE          NOT NULL,
    start_time         TIME          NULL,
    end_time           TIME          NULL,
    target_type        VARCHAR(20)   NOT NULL DEFAULT 'ALL',  -- ENUM: ALL, CLASS
    class_id           BIGINT        NULL,          -- rea_classes.id (논리 FK)
    class_name         VARCHAR(100)  NULL,          -- 스냅샷
    consultation_id    BIGINT        NULL,          -- rea_consultations.id (논리 FK)
    consultation_title VARCHAR(200)  NULL,          -- 스냅샷
    memo               VARCHAR(2000) NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NULL,
    PRIMARY KEY (id),
    KEY idx_schedules_academy_date (academy_id, date, start_time, id),
    KEY idx_schedules_consultation (academy_id, consultation_id, date, start_time, id),
    KEY idx_schedules_title (academy_id, title, date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rea_teacher_assignments (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    academy_id  BIGINT        NOT NULL,
    teacher_id  BIGINT        NOT NULL,              -- rea_members.id (논리 FK)
    duty_memo   VARCHAR(2000) NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_rea_teacher_assignment (academy_id, teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- [논리 FK 목록] — JPA 엔티티에 물리 FK가 없으므로 애플리케이션 레벨에서 정합성 보장.
-- 운영 모니터링 시 아래 관계의 고아 레코드 여부를 주기적으로 점검할 것.
--   rea_classes.teacher_id            → rea_members.id
--   rea_class_enrollments.class_id    → rea_classes.id
--   rea_class_enrollments.student_id  → rea_students.id
--   rea_attendance_events.student_id  → rea_students.id
--   rea_attendance_events.class_id    → rea_classes.id
--   rea_attendance_corrections.event_id → rea_attendance_events.id
--   rea_notifications.recipient_student_id → rea_students.id
--   rea_notification_settings.student_id   → rea_students.id
--   rea_notices.class_id             → rea_classes.id
--   rea_notices.author_id            → rea_members.id
--   rea_notice_reads.notice_id        → rea_notices.id
--   rea_notice_reads.student_id       → rea_students.id
--   rea_consultations.student_id      → rea_students.id
--   rea_consultation_messages.consultation_id → rea_consultations.id
--   rea_consultation_messages.sender_member_id → rea_members.id
--   rea_consultation_messages.sender_student_id → rea_students.id
--   rea_grades.student_id            → rea_students.id
--   rea_homework.class_id            → rea_classes.id
--   rea_homework.student_id          → rea_students.id
--   rea_homework_question_refs.homework_id → rea_homework.id
--   rea_homework_checks.homework_id  → rea_homework.id
--   rea_homework_checks.student_id   → rea_students.id
--   rea_progress.student_id          → rea_students.id
--   rea_schedules.class_id           → rea_classes.id
--   rea_schedules.consultation_id    → rea_consultations.id
--   rea_teacher_assignments.teacher_id → rea_members.id
-- ============================================================================
-- [인덱스 정리]
--   - 위 KEY들은 Repository 쿼리 메서드(findByAcademyIdAnd… 등) 패턴에서 도출.
--     기존 코드에 @Index 선언이 없으므로 인덱스는 운영 성능 기준 권장치이며,
--     신규 마이그레이션 작성 시 조회 패턴 변경에 맞춰 추가/조정할 것.
--   - uk_* : 엔티티 @UniqueConstraint와 1:1 대응 (auth_key, academy+student+type,
--     academy+notice+student, homework+student, academy, academy+teacher)
--   - FULLTEXT ft_notices_title: 공지 제목 검색 전용. 필요 없으면 제거 가능
-- ============================================================================
