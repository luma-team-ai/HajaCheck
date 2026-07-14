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
 * 초기화 스크립트가 서버 스키마(v0.3)의 enum 타입 3개 + users 테이블을 만들고, Hibernate 는 validate 로 대조한다.
 */
public abstract class PostgresTestSupport {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withInitScript("db/testcontainers-users-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 서버와 동일하게 스키마는 미리 만들어져 있고 엔티티는 대조만 한다.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}
