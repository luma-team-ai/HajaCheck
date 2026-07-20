package com.hajacheck.core.report.config;

import java.nio.file.Paths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 보고서 PDF 저장 @ConfigurationProperties 등록(스캔 미사용, 명시 등록) — MediaConfig 패턴과 동일.
 * 이 등록은 전 프로파일 공통(정적 서빙 여부와 무관하게 서비스가 프로퍼티를 필요로 함).
 *
 * <p>보고서 PDF(base-url-path)는 시설물 하자 정보를 담은 민감문서다.
 * ⚠️ <b>prod 정적 서빙 금지</b> — 사업자등록증(CompanyAuthConfig.FileStaticResourceConfig)과 동일한 정책으로,
 * 인가(소유자/관리자) 게이트를 건 다운로드 엔드포인트는 후속 이슈로 남긴다.
 * 그 전까지 정적 리소스 매핑은 dev/local/docker 에서만 등록하고 prod 에서는 미등록한다(무인가 노출 차단).
 */
@Configuration
@EnableConfigurationProperties(ReportPdfStorageProperties.class)
public class ReportPdfConfig {

    /**
     * 저장된 보고서 PDF 파일을 base-url-path 로 서빙하는 정적 리소스 매핑
     * (CompanyAuthConfig.FileStaticResourceConfig 와 동일 패턴).
     * ⚠️ prod 정적 서빙 금지 → @Profile("!prod") 로 prod 에서는 이 설정 자체를 등록하지 않는다
     * (민감문서 무인가 노출 차단). 인가 게이트 다운로드 엔드포인트는 후속 이슈.
     */
    @Configuration
    @Profile("!prod")
    static class ReportPdfStaticResourceConfig implements WebMvcConfigurer {

        private final ReportPdfStorageProperties properties;

        ReportPdfStaticResourceConfig(ReportPdfStorageProperties properties) {
            this.properties = properties;
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            String urlPattern = properties.getBaseUrlPath();
            if (!urlPattern.endsWith("/")) {
                urlPattern = urlPattern + "/";
            }
            String location = Paths.get(properties.getBaseDir())
                    .toAbsolutePath().normalize().toUri().toString();
            registry.addResourceHandler(urlPattern + "**")
                    .addResourceLocations(location);
        }
    }
}
