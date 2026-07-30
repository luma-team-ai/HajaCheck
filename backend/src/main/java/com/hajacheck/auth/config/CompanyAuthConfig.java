package com.hajacheck.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 기업 인증 관련 @ConfigurationProperties 등록(스캔 미사용, 명시 등록).
 * 이 등록은 전 프로파일 공통(정적 서빙 여부와 무관하게 서비스가 프로퍼티를 필요로 함).
 *
 * <p>사업자등록증 파일은 대표자 개인정보를 포함한 민감문서다. 정적 리소스 매핑을 두지 않고
 * 인가된 다운로드 엔드포인트에서만 회사 스코프를 검증한 뒤 반환한다.
 */
@Configuration
@EnableConfigurationProperties({
        FileStorageProperties.class, PolicyProperties.class, AuthProperties.class, AppMailProperties.class})
public class CompanyAuthConfig {
}
