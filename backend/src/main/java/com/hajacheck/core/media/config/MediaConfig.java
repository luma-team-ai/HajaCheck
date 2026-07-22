package com.hajacheck.core.media.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 점검 미디어 업로드 @ConfigurationProperties 등록(스캔 미사용, 명시 등록) — CompanyAuthConfig 패턴과 동일.
 */
@Configuration
@EnableConfigurationProperties(MediaUploadProperties.class)
public class MediaConfig {
}
