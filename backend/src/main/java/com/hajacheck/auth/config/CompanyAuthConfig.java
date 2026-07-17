package com.hajacheck.auth.config;

import java.nio.file.Paths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 기업 인증 관련 @ConfigurationProperties 등록(스캔 미사용, 명시 등록).
 * 이 등록은 전 프로파일 공통(정적 서빙 여부와 무관하게 서비스가 프로퍼티를 필요로 함).
 *
 * <p>사업자등록증 파일(/files/**)은 대표자 개인정보를 포함한 민감문서다.
 * ⚠️ <b>prod 정적 서빙 금지</b> — 인가(소유자/관리자) 게이트를 건 다운로드 엔드포인트는 후속(#194 하드닝 묶음).
 * 그 전까지 정적 리소스 매핑은 dev/local/docker 에서만 등록하고 prod 에서는 미등록한다(무인가 노출 차단).
 * (prod 엔 아직 파일 조회 소비처가 없어 기능 영향 없음.)
 */
@Configuration
@EnableConfigurationProperties({
        FileStorageProperties.class, PolicyProperties.class, AuthProperties.class, AppMailProperties.class})
public class CompanyAuthConfig {

    /**
     * 저장된 사업자등록증 파일을 base-url-path 로 서빙하는 정적 리소스 매핑.
     * ⚠️ prod 정적 서빙 금지 → @Profile("!prod") 로 prod 에서는 이 설정 자체를 등록하지 않는다
     * (민감문서 무인가 노출 차단). 인가 게이트 다운로드 엔드포인트는 후속(#194).
     */
    @Configuration
    @Profile("!prod")
    static class FileStaticResourceConfig implements WebMvcConfigurer {

        private final FileStorageProperties fileStorageProperties;

        FileStaticResourceConfig(FileStorageProperties fileStorageProperties) {
            this.fileStorageProperties = fileStorageProperties;
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            String urlPattern = fileStorageProperties.getBaseUrlPath();
            if (!urlPattern.endsWith("/")) {
                urlPattern = urlPattern + "/";
            }
            String location = Paths.get(fileStorageProperties.getBaseDir())
                    .toAbsolutePath().normalize().toUri().toString();
            registry.addResourceHandler(urlPattern + "**")
                    .addResourceLocations(location);
        }
    }
}
