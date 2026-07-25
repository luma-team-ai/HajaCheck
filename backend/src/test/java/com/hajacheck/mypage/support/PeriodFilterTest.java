package com.hajacheck.mypage.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PeriodFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 26);

    @Test
    void fromCode_프론트값을그대로매핑() {
        assertThat(PeriodFilter.fromCode("1M")).isEqualTo(PeriodFilter.ONE_MONTH);
        assertThat(PeriodFilter.fromCode("3M")).isEqualTo(PeriodFilter.THREE_MONTHS);
        assertThat(PeriodFilter.fromCode("6M")).isEqualTo(PeriodFilter.SIX_MONTHS);
        assertThat(PeriodFilter.fromCode("1Y")).isEqualTo(PeriodFilter.ONE_YEAR);
        assertThat(PeriodFilter.fromCode("ALL")).isEqualTo(PeriodFilter.ALL);
    }

    @Test
    void fromCode_미지정null은ALL로취급() {
        assertThat(PeriodFilter.fromCode(null)).isEqualTo(PeriodFilter.ALL);
    }

    @Test
    void fromCode_인식불가코드는INVALID_INPUT() {
        assertThatThrownBy(() -> PeriodFilter.fromCode("2M"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void cutoffDate_ALL은하한없음() {
        assertThat(PeriodFilter.ALL.cutoffDate(TODAY)).isEqualTo(Optional.empty());
    }

    @Test
    void cutoffDate_개월수만큼과거로계산() {
        assertThat(PeriodFilter.ONE_MONTH.cutoffDate(TODAY)).contains(LocalDate.of(2026, 6, 26));
        assertThat(PeriodFilter.THREE_MONTHS.cutoffDate(TODAY)).contains(LocalDate.of(2026, 4, 26));
        assertThat(PeriodFilter.SIX_MONTHS.cutoffDate(TODAY)).contains(LocalDate.of(2026, 1, 26));
        assertThat(PeriodFilter.ONE_YEAR.cutoffDate(TODAY)).contains(LocalDate.of(2025, 7, 26));
    }
}
