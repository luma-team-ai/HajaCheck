package com.hajacheck.core.report.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 보고서 PDF 저장 @ConfigurationProperties 등록(스캔 미사용, 명시 등록) — MediaConfig 패턴과 동일.
 */
@Configuration
@EnableConfigurationProperties(ReportPdfStorageProperties.class)
public class ReportPdfConfig {
}
