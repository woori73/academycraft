#!/bin/bash
# 운영 읽기 전용 점검(고정 종류만). 임의 명령·SQL 입력 불가. DB는 READ ONLY 트랜잭션 + SELECT 집계만, 개인정보·비밀값 출력 없음.
# 사용: diagnose-readonly.sh <notice-census|timezone>
# 테스트용 override: ENV_FILE(기본 /etc/academycraft/env), MYSQL_BIN(기본 mysql)
set -uo pipefail
KIND="${1:-}"
ENV_FILE="${ENV_FILE:-/etc/academycraft/env}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

# env 파일은 하위 셸에서만 읽고 값은 출력하지 않는다.
db_query() {
  ( set -a; . "$ENV_FILE"; set +a
    HOST=$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:mysql://([^:/?]+).*#\1#')
    PORT=$(printf '%s' "$DB_URL" | sed -nE 's#^jdbc:mysql://[^:/?]+:([0-9]+).*#\1#p'); PORT="${PORT:-3306}"
    NAME=$(printf '%s' "$DB_URL" | sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#')
    MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" -h"$HOST" -P"$PORT" -u"$DB_USERNAME" "$NAME" \
      --init-command="SET SESSION TRANSACTION READ ONLY" --table -e "$1" )
}

case "$KIND" in
  notice-census)
    echo "== 공지 건수: 학원별 전체/활성/작성자 NULL (건수만) =="
    db_query "SELECT academy_id, COUNT(*) AS total, SUM(active=1) AS active_cnt, SUM(author_id IS NULL) AS author_null, SUM(active=1 AND author_id IS NULL) AS active_author_null FROM rea_notices GROUP BY academy_id ORDER BY academy_id"
    echo "== 작성자가 없거나 비활성/타 학원인 회원을 가리키는 공지 건수 =="
    db_query "SELECT n.academy_id, SUM(m.id IS NULL) AS author_missing, SUM(m.id IS NOT NULL AND m.active=0) AS author_inactive, SUM(m.id IS NOT NULL AND m.academy_id<>n.academy_id) AS author_other_academy FROM rea_notices n LEFT JOIN rea_members m ON m.id=n.author_id WHERE n.author_id IS NOT NULL GROUP BY n.academy_id ORDER BY n.academy_id"
    echo "== 생성일 범위(시드/실사용 구분 참고, 날짜만) =="
    db_query "SELECT academy_id, DATE(MIN(created_at)) AS first_created, DATE(MAX(created_at)) AS last_created FROM rea_notices GROUP BY academy_id ORDER BY academy_id"
    ;;
  timezone)
    echo "== 호스트 시간대 =="
    timedatectl 2>&1 | grep -E "Local time|Universal time|Time zone|System clock" || true
    date '+%Y-%m-%d %H:%M:%S %Z %z'
    echo "== AcademyCraft JVM =="
    PID=$(systemctl show -p MainPID --value academycraft 2>/dev/null)
    if [ -n "$PID" ] && [ "$PID" != 0 ]; then
      echo "pid=$PID"
      echo -n "TZ env: "; (tr '\0' '\n' < "/proc/$PID/environ" | grep -E '^TZ=' || echo "(unset)")
      echo -n "-Duser.timezone: "; (tr '\0' '\n' < "/proc/$PID/cmdline" | grep -E '^-Duser.timezone' || echo "(none)")
      echo -n "unit/env TZ settings: "; (grep -hE '^(Environment=.*TZ=|TZ=)' /etc/systemd/system/academycraft.service /etc/academycraft/env 2>/dev/null | sed -E 's/(PASSWORD|SECRET)=.*/\1=<redacted>/' || true); echo
    else
      echo "academycraft 서비스 PID를 찾지 못했습니다."
    fi
    echo "== DB 시간대 =="
    db_query "SELECT NOW() AS db_now, UTC_TIMESTAMP() AS db_utc, @@session.time_zone AS session_tz, @@global.time_zone AS global_tz, @@system_time_zone AS system_tz"
    echo "== 출결 시각 분포(시간대 어긋남 판단용 집계, 개인정보 없음) =="
    db_query "SELECT HOUR(occurred_at) AS hour_of_day, COUNT(*) AS cnt FROM rea_attendance_events GROUP BY HOUR(occurred_at) ORDER BY hour_of_day"
    db_query "SELECT COUNT(*) AS events, MIN(occurred_at) AS first_at, MAX(occurred_at) AS last_at, MAX(created_at) AS last_created FROM rea_attendance_events"
    ;;
  *)
    echo "허용되지 않은 종류: $KIND (notice-census | timezone)" >&2
    exit 2
    ;;
esac
