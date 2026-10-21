package com.rea.academy.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/** 시간대 원칙: JVM 기본 시간대(운영은 UTC)와 무관하게 항상 Asia/Seoul 벽시계 시각이다. */
class ReaTimeTest {

    private TimeZone original;

    @BeforeEach
    void saveDefault() {
        original = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefault() {
        TimeZone.setDefault(original);
    }

    private static LocalDateTime seoulNow() {
        return ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toLocalDateTime();
    }

    @Test
    @DisplayName("JVM 기본 시간대가 UTC여도 now()는 한국 시각이다")
    void givenUtcJvm_whenNow_thenKoreanWallClock() {
        // given
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // when
        LocalDateTime now = ReaTime.now();
        // then
        assertThat(Duration.between(now, seoulNow()).abs()).isLessThan(Duration.ofSeconds(5));
        assertThat(Duration.between(LocalDateTime.now(), now).toHours()).isIn(8L, 9L); // UTC보다 9시간 앞
    }

    @Test
    @DisplayName("JVM 기본 시간대가 다른 곳(LA)이어도 today()는 한국 날짜다")
    void givenLosAngelesJvm_whenToday_thenKoreanDate() {
        // given
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        // when / then
        assertThat(ReaTime.today()).isIn(seoulNow().toLocalDate(), seoulNow().minusSeconds(5).toLocalDate());
    }

    @Test
    @DisplayName("JVM 기본 시간대가 이미 Asia/Seoul이면 기존 동작과 같다")
    void givenSeoulJvm_whenNow_thenSameAsLocalDateTimeNow() {
        // given
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        // when / then
        assertThat(Duration.between(ReaTime.now(), LocalDateTime.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("시간대 상수는 Asia/Seoul이다")
    void zoneIsAsiaSeoul() {
        assertThat(ReaTime.ZONE).isEqualTo(ZoneId.of("Asia/Seoul"));
    }
}
