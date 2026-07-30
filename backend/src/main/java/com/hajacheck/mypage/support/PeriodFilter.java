package com.hajacheck.mypage.support;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 마이페이지 "내 점검 이력 / 보고서" 조회 기간 필터(#844) — 프론트 {@code PeriodFilterSelect}
 * (1M/3M/6M/1Y/ALL) 값을 쿼리 파라미터 {@code period}로 그대로 받는다. 자바 enum 상수명은 숫자로
 * 시작할 수 없어 코드 문자열 ↔ enum 매핑은 {@link #fromCode(String)}이 전담한다.
 */
public enum PeriodFilter {
    ONE_MONTH(1),
    THREE_MONTHS(3),
    SIX_MONTHS(6),
    ONE_YEAR(12),
    ALL(null);

    private final Integer months;

    PeriodFilter(Integer months) {
        this.months = months;
    }

    /** 미지정(null)은 ALL로 취급 — 나머지 인식 불가 코드는 INVALID_INPUT(400). */
    public static PeriodFilter fromCode(String code) {
        if (code == null) {
            return ALL;
        }
        return switch (code) {
            case "1M" -> ONE_MONTH;
            case "3M" -> THREE_MONTHS;
            case "6M" -> SIX_MONTHS;
            case "1Y" -> ONE_YEAR;
            case "ALL" -> ALL;
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        };
    }

    /** {@code today} 기준 조회 시작일(포함). ALL이면 하한 없음(empty). */
    public Optional<LocalDate> cutoffDate(LocalDate today) {
        return months == null ? Optional.empty() : Optional.of(today.minusMonths(months));
    }
}
