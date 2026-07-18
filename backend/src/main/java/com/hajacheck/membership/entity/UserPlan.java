package com.hajacheck.membership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 개인/회사 구독 — DDL user_plans 테이블 대응. auth 도메인의 사용자·회사는 식별자로 유지하고,
 * 같은 membership 도메인의 Plan은 지연 로딩 관계를 함께 제공한다.
 *
 * <p>owner XOR(userId 또는 companyId 중 정확히 하나)는 DB 제약({@code ck_user_plans_owner_xor})이 보장하며,
 * 이 엔티티는 팩토리 메서드 {@link #forUser}/{@link #forCompany} 로만 생성해 애플리케이션 레벨에서도 XOR 을 강제한다.
 *
 * <p>created_at/updated_at 컬럼이 DDL 에 없어 BaseTimeEntity 를 상속하지 않는다(startedAt 이 생성시각 역할).
 */
@Entity
@Getter
@Table(name = "user_plans", indexes = {
        @Index(name = "idx_user_plans_user", columnList = "user_id"),
        @Index(name = "idx_user_plans_company", columnList = "company_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", insertable = false, updatable = false)
    private Plan plan;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "user_plan_status_type", nullable = false)
    private UserPlanStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private UserPlan(Long userId, Long companyId, Long planId, UserPlanStatus status, Instant startedAt) {
        this.userId = userId;
        this.companyId = companyId;
        this.planId = planId;
        this.status = status == null ? UserPlanStatus.ACTIVE : status;
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    /** 개인 구독 생성 팩토리 — companyId 는 null(owner XOR). */
    public static UserPlan forUser(Long userId, Long planId) {
        return UserPlan.builder()
                .userId(userId)
                .planId(planId)
                .status(UserPlanStatus.ACTIVE)
                .build();
    }

    /** 회사 구독 생성 팩토리 — userId 는 null(owner XOR). */
    public static UserPlan forCompany(Long companyId, Long planId) {
        return UserPlan.builder()
                .companyId(companyId)
                .planId(planId)
                .status(UserPlanStatus.ACTIVE)
                .build();
    }

    /**
     * 업그레이드 문의(PG 실결제 대체) — status를 UPGRADE_REQUESTED 로 전이한다.
     * 멱등: 이미 UPGRADE_REQUESTED 면 no-op(계약: "이미 요청 상태면 그대로 200").
     */
    public void requestUpgrade() {
        if (this.status == UserPlanStatus.UPGRADE_REQUESTED) {
            return;
        }
        this.status = UserPlanStatus.UPGRADE_REQUESTED;
    }

    public boolean isOwnedByUser(Long candidateUserId) {
        return this.userId != null && this.userId.equals(candidateUserId);
    }
}
