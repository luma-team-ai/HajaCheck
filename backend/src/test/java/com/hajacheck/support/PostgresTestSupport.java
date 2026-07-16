package com.hajacheck.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * DB 접근 테스트 공용 베이스 — 실 PostgreSQL 16(Testcontainers)에 붙는다.
 *
 * <p>이유: {@code @JdbcTypeCode(NAMED_ENUM)} 으로 매핑한 PG named enum(role_type 등)은 H2 로 재현할 수
 * 없어, enum 이 걸린 User 엔티티/리포지토리는 실 PG 에서만 정합성(ddl-auto=validate)을 검증할 수 있다.
 *
 * <p>컨테이너는 단일 인스턴스(static)로 한 번만 기동해 모든 테스트 클래스가 재사용한다(Ryuk 가 JVM 종료 시 정리).
 * 초기화 스크립트는 빌드 시 설계 기준 DDL에서 복사되며, Hibernate 는 전체 스키마를 validate 로 대조한다.
 */
public abstract class PostgresTestSupport {

    private static final String EXTERNAL_URL = System.getenv("TEST_POSTGRES_URL");
    private static final String EXTERNAL_USERNAME = System.getenv("TEST_POSTGRES_USERNAME");
    private static final String EXTERNAL_PASSWORD = System.getenv("TEST_POSTGRES_PASSWORD");

    static final PostgreSQLContainer<?> POSTGRES = createContainerWhenNeeded();

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    private static PostgreSQLContainer<?> createContainerWhenNeeded() {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            return null;
        }
        return new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("hajacheck")
                .withUsername("postgres")
                .withPassword("postgres")
                .withInitScript("db/HajaCheck_script.sql");
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES == null ? EXTERNAL_URL : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username",
                () -> POSTGRES == null ? EXTERNAL_USERNAME : POSTGRES.getUsername());
        registry.add("spring.datasource.password",
                () -> POSTGRES == null ? EXTERNAL_PASSWORD : POSTGRES.getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 서버와 동일하게 스키마는 미리 만들어져 있고 엔티티는 대조만 한다.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}
