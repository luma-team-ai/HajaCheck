package com.hajacheck.auth.support;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link SmtpConfiguredCondition} 의 부정 — 로컬/dev 로그 폴백 선택용.
 * 두 조건이 상호배타라 {@link PasswordResetMailSender} 빈은 항상 정확히 하나만 뜬다.
 */
public class SmtpNotConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !SmtpConfiguredCondition.isSmtpConfigured(context.getEnvironment());
    }
}
