package com.hajacheck.auth.config;

import java.nio.file.Paths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 기업 인증 관련 @ConfigurationProperties 등록(스캔 미사용, 명시 등록) +
 * 저장된 사업자등록증 파일을 base-url-path 로 서빙하는 정적 리소스 매핑.
 *
 * <p>주의: 사업자등록증은 민감 문서다. 현재는 dev 편의를 위해 정적 서빙하되, 실서비스 전환 시
 * 인가(소유자/관리자만) 게이트를 건 다운로드 엔드포인트로 교체하는 것이 후속 과제다.
 */
@Configuration
@EnableConfigurationProperties({FileStorageProperties.class, PolicyProperties.class, AuthProperties.class})
public class CompanyAuthConfig implements WebMvcConfigurer {

    private final FileStorageProperties fileStorageProperties;

    public CompanyAuthConfig(FileStorageProperties fileStorageProperties) {
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
