package com.hajacheck.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 약관·개인정보 처리방침 버전별 동의 이력 — DDL user_consents 테이블 대응.
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: user_consents 테이블에는 updated_at 컬럼이 없다(agreed_at 만 존재).
 * BaseTimeEntity 를 상속하면 updated_at 매핑이 생겨 ddl-auto=validate 가 실패한다.
 *
 * <p>policy_type 은 PG named enum(consent_policy_type) — @JdbcTypeCode(NAMED_ENUM) 매핑.
 */
@Entity
@Getter
@Table(name = "user_consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "policy_type", columnDefinition = "consent_policy_type", nullable = false)
    private ConsentPolicyType policyType;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private UserConsent(Long userId, ConsentPolicyType policyType, String policyVersion, Instant agreedAt) {
        this.userId = userId;
        this.policyType = policyType;
        this.policyVersion = policyVersion;
        this.agreedAt = agreedAt;
    }

    /**
     * 동의 이력 생성 팩토리 — 동의 시각은 생성 시점(now).
     */
    public static UserConsent of(Long userId, ConsentPolicyType policyType, String policyVersion) {
        return UserConsent.builder()
                .userId(userId)
                .policyType(policyType)
                .policyVersion(policyVersion)
                .agreedAt(Instant.now())
                .build();
    }
}
