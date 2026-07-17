package com.hajacheck.membership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 구독(개인/회사)별 월간 사용량 집계 — DDL usage_counters 테이블 대응(v0.3, 365행~).
 * userPlanId 호환 필드와 같은 membership 도메인의 UserPlan 지연 로딩 관계를 함께 제공한다.
 *
 * <p>DDL 에 updated_at 컬럼이 없어 BaseTimeEntity 를 상속하지 않고 createdAt 만 자체 관리한다.
 * period 는 항상 해당 월 1일로 저장(DB 제약 ck_usage_counters_period_month_start).
 *
 * <p>⚠️ 이 Entity에는 의도적으로 {@code @Version}을 두지 않는다. 카운터 증가는 JPA 낙관적 락(읽기→증가→저장)이
 * 아니라 table_design.md §usage_counters "동시성 정책 확정"이 규정한 **원자적 조건부 UPDATE**
 * (예: {@code UPDATE ... SET count = count + 1 WHERE ... AND count < :limit RETURNING ...})로만
 * 수행해야 한다 — 갱신 행 수 0이 곧 한도 초과 판정이며, period 최초 생성 경합은 UNIQUE 기반 UPSERT로 흡수한다.
 * 향후 이 카운터를 증가시키는 서비스(QuotaInterceptor 등)를 구현할 때 read-modify-write 패턴으로
 * 되돌리지 않도록 주의한다(jpa_entity_implementation_audit.md §3.7 참조).
 */
@Entity
@Getter
@Table(
        name = "usage_counters",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_usage_counters_user_plan_period",
                columnNames = {"user_plan_id", "period"}))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageCounter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_plan_id", nullable = false)
    private Long userPlanId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_plan_id", insertable = false, updatable = false)
    private UserPlan userPlan;

    @Column(nullable = false)
    private LocalDate period;

    @Column(name = "analyzed_image_count", nullable = false)
    private int analyzedImageCount;

    @Column(name = "facility_count", nullable = false)
    private int facilityCount;

    @Column(name = "analysis_request_count", nullable = false)
    private int analysisRequestCount;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "counsel_ticket_count", nullable = false)
    private int counselTicketCount;

    @Column(name = "pdf_generation_count", nullable = false)
    private int pdfGenerationCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder(access = AccessLevel.PRIVATE)
    private UsageCounter(Long userPlanId, LocalDate period, int analyzedImageCount, int facilityCount,
                         int analysisRequestCount, int seatCount, int counselTicketCount,
                         int pdfGenerationCount) {
        this.userPlanId = userPlanId;
        this.period = period.withDayOfMonth(1);
        this.analyzedImageCount = analyzedImageCount;
        this.facilityCount = facilityCount;
        this.analysisRequestCount = analysisRequestCount;
        this.seatCount = seatCount;
        this.counselTicketCount = counselTicketCount;
        this.pdfGenerationCount = pdfGenerationCount;
    }

    /** 당월 집계 행 생성 팩토리(시드/테스트용) — period 는 자동으로 해당 월 1일로 정규화된다. */
    public static UsageCounter create(Long userPlanId, LocalDate period, int analyzedImageCount,
                                      int facilityCount, int analysisRequestCount, int seatCount,
                                      int counselTicketCount, int pdfGenerationCount) {
        return UsageCounter.builder()
                .userPlanId(userPlanId)
                .period(period)
                .analyzedImageCount(analyzedImageCount)
                .facilityCount(facilityCount)
                .analysisRequestCount(analysisRequestCount)
                .seatCount(seatCount)
                .counselTicketCount(counselTicketCount)
                .pdfGenerationCount(pdfGenerationCount)
                .build();
    }
}
