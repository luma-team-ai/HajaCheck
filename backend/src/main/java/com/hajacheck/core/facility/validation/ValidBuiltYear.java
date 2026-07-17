package com.hajacheck.core.facility.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * 시설물 준공년도(builtYear) 범위 검증(#351) — 1900 ~ 현재연도+1.
 *
 * <p>상한이 <b>동적</b>(현재연도+1)이라 {@code @Max} 로 표현할 수 없어 커스텀 제약으로 둔다.
 * {@code @Max(2100)} 같은 정적 상한은 미래 수십 년을 허용해 "준공년도는 미래일 수 없다"는
 * 도메인 불변식을 지키지 못하고, FE(#352: 1900~현재연도+1) 와 범위가 어긋나 API 직접 호출로
 * 우회 가능해진다 — 서버측이 실질 방어여야 하므로 FE 와 동일 범위를 서버에서 강제한다.
 *
 * <p>+1 은 "내년 준공 예정" 등록을 허용하기 위함(#352 와 동일 근거).
 * null 은 통과시킨다 — builtYear 는 DDL NULL 허용(선택 입력)이며 필수 여부는 별도 제약의 몫이다.
 */
@Documented
@Constraint(validatedBy = BuiltYearValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface ValidBuiltYear {

    String message() default "준공년도는 1900년부터 내년까지만 입력할 수 있습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
