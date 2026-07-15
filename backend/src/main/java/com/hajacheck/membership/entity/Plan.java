package com.hajacheck.membership.entity;

import com.hajacheck.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 구독 요금제 — DDL plans 테이블 대응(v0.3, 279행~). 조회 전용(관리자 콘솔 등에서만 변경, 이번 범위 아님).
 *
 * <p>name 은 PG named enum(plan_name_type) — {@code @JdbcTypeCode(NAMED_ENUM)} + columnDefinition 으로
 * 실 PG enum 에 매핑한다(ddl-auto=validate 통과, User.role 매핑 패턴 참고).
 *
 * <p>maxFacilities/maxMonthlyAnalyses 는 DDL 상 nullable(무제한 의미) — null 이면 응답에도 null 그대로 반환한다(계약).
 */
@Entity
@Getter
@Table(name = "plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Plan extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "plan_name_type", nullable = false, unique = true)
    private PlanName name;

    @Column(name = "max_facilities")
    private Integer maxFacilities;

    @Column(name = "max_monthly_analyses")
    private Integer maxMonthlyAnalyses;

    @Column(name = "max_seats", nullable = false)
    private Integer maxSeats;

    @Column(name = "has_pdf_watermark", nullable = false)
    private boolean hasPdfWatermark;

    @Column(name = "has_counselor_access", nullable = false)
    private boolean hasCounselorAccess;

    @Column(name = "has_ai_addon", nullable = false)
    private boolean hasAiAddon;

    @Column(name = "price_monthly", precision = 10, scale = 2)
    private BigDecimal priceMonthly;

    @Builder(access = AccessLevel.PRIVATE)
    private Plan(PlanName name, Integer maxFacilities, Integer maxMonthlyAnalyses, Integer maxSeats,
                boolean hasPdfWatermark, boolean hasCounselorAccess, boolean hasAiAddon,
                BigDecimal priceMonthly) {
        this.name = name;
        this.maxFacilities = maxFacilities;
        this.maxMonthlyAnalyses = maxMonthlyAnalyses;
        this.maxSeats = maxSeats == null ? 0 : maxSeats;
        this.hasPdfWatermark = hasPdfWatermark;
        this.hasCounselorAccess = hasCounselorAccess;
        this.hasAiAddon = hasAiAddon;
        this.priceMonthly = priceMonthly;
    }

    /**
     * 요금제 기준행 생성 팩토리(시드/테스트용). maxFacilities/maxMonthlyAnalyses 에 null 을 넘기면
     * 무제한 요금제(Enterprise 등)를 표현한다.
     */
    public static Plan create(PlanName name, Integer maxFacilities, Integer maxMonthlyAnalyses, Integer maxSeats,
                              boolean hasPdfWatermark, boolean hasCounselorAccess, boolean hasAiAddon,
                              BigDecimal priceMonthly) {
        return Plan.builder()
                .name(name)
                .maxFacilities(maxFacilities)
                .maxMonthlyAnalyses(maxMonthlyAnalyses)
                .maxSeats(maxSeats)
                .hasPdfWatermark(hasPdfWatermark)
                .hasCounselorAccess(hasCounselorAccess)
                .hasAiAddon(hasAiAddon)
                .priceMonthly(priceMonthly)
                .build();
    }
}
