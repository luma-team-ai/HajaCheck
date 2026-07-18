package com.hajacheck.core.facility.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * BuiltYearValidator 단위 테스트(#351). 스프링 컨텍스트·DB 불요.
 * 상한이 "현재연도+1"로 동적이라, 고정 Clock 을 주입해 결정적으로 검증한다.
 */
class BuiltYearValidatorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-07-17 KST 고정 → 허용 상한 = 2027
    private static final Clock FIXED_2026 = Clock.fixed(Instant.parse("2026-07-16T15:00:00Z"), KST);

    private final BuiltYearValidator validator = new BuiltYearValidator(FIXED_2026);

    @Test
    @DisplayName("null 은 선택 입력이므로 통과한다")
    void nullIsValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {1900, 1999, 2008, 2026, 2027})
    @DisplayName("1900 ~ 현재연도+1(2027) 은 통과한다")
    void inRange_isValid(int builtYear) {
        assertThat(validator.isValid(builtYear, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {-100, 0, 1899, 2028, 999999, Integer.MAX_VALUE})
    @DisplayName("범위 밖은 거부한다 — 이슈 #351이 지적한 -100/999999/Integer.MAX_VALUE 포함")
    void outOfRange_isInvalid(int builtYear) {
        assertThat(validator.isValid(builtYear, null)).isFalse();
    }

    @Test
    @DisplayName("상한은 정적 상수가 아니라 현재연도를 따라 움직인다")
    void upperBoundFollowsCurrentYear() {
        BuiltYearValidator in2030 =
                new BuiltYearValidator(Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), KST));

        // 2031 은 2030 기준 상한(현재연도+1)이라 통과 — 정적 @Max 로는 표현할 수 없는 지점.
        assertThat(in2030.isValid(2031, null)).isTrue();
        assertThat(in2030.isValid(2032, null)).isFalse();
        // 같은 2031 이 2026 기준에서는 거부돼야 한다(상한이 고정이 아님을 대비 확인).
        assertThat(validator.isValid(2031, null)).isFalse();
    }
}
