package com.hajacheck.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 데모 도메인 @ConfigurationProperties 등록(스캔 미사용, 명시 등록 — CompanyAuthConfig 패턴).
 * 데모 로그인 설정({@code app.demo.*} → {@code DemoProperties})은 auth 도메인(CompanyAuthConfig)에서
 * 등록한다 — 여기는 리셋 스케줄러 설정만.
 */
@Configuration
@EnableConfigurationProperties(DemoResetProperties.class)
public class DemoConfig {
}
