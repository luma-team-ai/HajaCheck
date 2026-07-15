package com.hajacheck.membership.entity;

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
 * 개인/회사 구독 — DDL user_plans 테이블 대응(v0.3, 323행~). 연관관계 금지 원칙에 따라
 * userId/companyId/planId 는 모두 Long 값 컬럼으로만 보유한다(User/Company/Plan 엔티티 결합 금지).
 *
 * <p>owner XOR(userId 또는 companyId 중 정확히 하나)는 DB 제약({@code ck_user_plans_owner_xor})이 보장하며,
 * 이 엔티티는 팩토리 메서드 {@link #forUser}/{@link #forCompany} 로만 생성해 애플리케이션 레벨에서도 XOR 을 강제한다.
 *
 * <p>created_at/updated_at 컬럼이 DDL 에 없어 BaseTimeEntity 를 상속하지 않는다(startedAt 이 생성시각 역할).
 */
@Entity
@Getter
@Table(name = "user_plans")
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
