package com.hajacheck.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 사업자등록증 정적 서빙(/files/**)이 어떤 프로파일에서도 등록되지 않는지 검증한다.
 */
class CompanyAuthConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CompanyAuthConfig.class);

    @Test
    void 기본프로파일_정적서빙매핑_미등록() {
        runner.run(ctx -> assertThat(ctx)
                .doesNotHaveBean(WebMvcConfigurer.class));
    }

    @Test
    void prod프로파일_정적서빙매핑_미등록() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> {
                    // 컨텍스트 자체는 정상 기동하되(ConfigurationProperties 는 전 프로파일 등록),
                    // prod 에서는 정적 리소스 매핑 설정 빈이 등록되지 않아야 한다.
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(WebMvcConfigurer.class);
                    // ConfigurationProperties 빈은 prod 에서도 유지(서비스가 필요로 함).
                    assertThat(ctx).hasSingleBean(FileStorageProperties.class);
                });
    }
}
