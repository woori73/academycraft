package com.rea.academy.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 서비스 전체의 "현재 시각" 기준.
 *
 * <p>원칙: DB의 DATETIME 컬럼과 API의 시각 문자열은 모두 <b>한국 표준시(Asia/Seoul) 벽시계 시각</b>이다.
 * JVM·호스트·DB 서버의 기본 시간대(운영은 UTC)에 의존하지 않도록 {@code LocalDateTime.now()} 대신 이 클래스를 쓴다.
 * "오늘"(출결부·대시보드·리포트 날짜 경계)도 {@link #today()}로 같은 기준을 쓴다.
 * (Connector/J의 serverTimezone 은 LocalDateTime 값을 바꾸지 않으므로 저장값은 항상 여기서 만든 값 그대로다.)
 */
public final class ReaTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.system(ZONE);

    private ReaTime() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(CLOCK);
    }

    public static LocalDate today() {
        return LocalDate.now(CLOCK);
    }
}
