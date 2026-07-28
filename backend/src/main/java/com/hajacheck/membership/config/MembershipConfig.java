package com.hajacheck.membership.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * membership 도메인 설정 바인딩 등록({@code BizVerifyConfig} 와 동일한 패턴 — 도메인별 설정 클래스에서
 * {@code @EnableConfigurationProperties} 로 묶는다).
 */
@Configuration
@EnableConfigurationProperties(PlanExpiryProperties.class)
public class MembershipConfig {
}
