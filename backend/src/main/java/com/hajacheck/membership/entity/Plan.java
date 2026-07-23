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
 * 구독 요금제 — DDL plans 테이블 대응(v0.3, 279행~). 플랫폼 관리자 "플랜 정책 설정"(#624 후속, 사용자
 * 지시)에서만 {@link #updatePolicy} 로 변경한다 — 그 외 화면(회사 관리자 플랜 변경 등)은 여전히 조회 전용.
 *
 * <p>name 은 PG named enum(plan_name_type) — {@code @JdbcTypeCode(NAMED_ENUM)} + columnDefinition 으로
 * 실 PG enum 에 매핑한다(ddl-auto=validate 통과, User.role 매핑 패턴 참고).
 *
 * <p>maxFacilities/maxMonthlyAnalyses/maxSeats 는 DDL 상 nullable(무제한 의미) — null 이면 응답에도 null 그대로
 * 반환한다(계약).
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

    @Column(name = "max_seats")
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
        this.maxSeats = maxSeats;
        this.hasPdfWatermark = hasPdfWatermark;
        this.hasCounselorAccess = hasCounselorAccess;
        this.hasAiAddon = hasAiAddon;
        this.priceMonthly = priceMonthly;
    }

    /**
     * 요금제 기준행 생성 팩토리(시드/테스트용). maxFacilities/maxMonthlyAnalyses/maxSeats 에 null 을 넘기면
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

    /**
     * 가격·한도·기능 제공 여부를 일괄 변경한다(플랫폼 관리자 "플랜 정책 설정"). hasAiAddon 은 이 화면의
     * 편집 대상이 아니라 파라미터에 없다 — 의도치 않게 false 로 덮어써지는 것을 막기 위해 아예 건드리지
     * 않는다(다른 값들과 달리 세터가 없으므로 실수로 리셋될 수 없다).
     */
    public void updatePolicy(BigDecimal priceMonthly, Integer maxFacilities, Integer maxMonthlyAnalyses,
                              Integer maxSeats, boolean hasPdfWatermark, boolean hasCounselorAccess) {
        this.priceMonthly = priceMonthly;
        this.maxFacilities = maxFacilities;
        this.maxMonthlyAnalyses = maxMonthlyAnalyses;
        this.maxSeats = maxSeats;
        this.hasPdfWatermark = hasPdfWatermark;
        this.hasCounselorAccess = hasCounselorAccess;
    }
}
