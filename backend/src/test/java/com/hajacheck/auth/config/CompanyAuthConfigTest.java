package com.hajacheck.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 사업자등록증 정적 서빙(/files/**) 프로파일 가드 검증.
 * prod 에서는 정적 리소스 매핑(FileStaticResourceConfig)이 등록되지 않아야 한다(민감문서 무인가 노출 차단, 후속 #194).
 */
class CompanyAuthConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CompanyAuthConfig.class);

    @Test
    void 비prod프로파일_정적서빙매핑_등록됨() {
        // 프로파일 미지정(=!prod 충족) → 정적 리소스 매핑 설정 빈이 존재해야 한다.
        runner.run(ctx -> assertThat(ctx)
                .hasSingleBean(CompanyAuthConfig.FileStaticResourceConfig.class));
    }

    @Test
    void prod프로파일_정적서빙매핑_미등록() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> {
                    // 컨텍스트 자체는 정상 기동하되(ConfigurationProperties 는 전 프로파일 등록),
                    // prod 에서는 정적 리소스 매핑 설정 빈이 등록되지 않아야 한다.
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(CompanyAuthConfig.FileStaticResourceConfig.class);
                    // ConfigurationProperties 빈은 prod 에서도 유지(서비스가 필요로 함).
                    assertThat(ctx).hasSingleBean(FileStorageProperties.class);
                });
    }
}
