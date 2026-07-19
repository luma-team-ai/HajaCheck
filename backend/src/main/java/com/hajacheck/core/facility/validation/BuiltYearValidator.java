package com.hajacheck.core.facility.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.Year;
import java.time.ZoneId;

/**
 * {@link ValidBuiltYear} 구현(#351). 1900 ~ 현재연도+1 범위를 검증한다.
 *
 * <p>기준 시각은 KST — 서버 타임존에 따라 연말/연초에 판정이 흔들리지 않도록 고정한다
 * (DashboardService 의 KST 집계 기준과 동일 원칙).
 * {@link Clock} 을 주입 가능하게 둬 테스트가 특정 연도를 결정적으로 재현할 수 있게 한다.
 */
public class BuiltYearValidator implements ConstraintValidator<ValidBuiltYear, Integer> {

    static final int MIN_BUILT_YEAR = 1900;
    /** 내년 준공 예정 등록 허용(#352 와 동일 근거). */
    static final int MAX_YEARS_AHEAD = 1;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    public BuiltYearValidator() {
        this(Clock.system(KST));
    }

    BuiltYearValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        // null 은 선택 입력이므로 통과 — 필수 여부는 @NotNull 등 별도 제약의 몫(Bean Validation 관례).
        if (value == null) {
            return true;
        }
        int maxBuiltYear = Year.now(clock).getValue() + MAX_YEARS_AHEAD;
        return value >= MIN_BUILT_YEAR && value <= maxBuiltYear;
    }
}
