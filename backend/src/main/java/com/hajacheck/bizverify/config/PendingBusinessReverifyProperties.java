package com.hajacheck.bizverify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PENDING 사업자 진위확인 자동 재검증 스케줄러(#888) 설정 — SpringBoot_코드_컨벤션.md §9: 매직넘버 금지.
 *
 * <p>이 스케줄러는 {@code NtsBusinessVerifyClient#verifyRealtime}을 직접 호출하며, 이 경로는 실시간
 * 진위확인 공개 API({@link BizVerifyProperties.RateLimit})의 rate-limit을 타지 않는다(공개 컨트롤러를
 * 거치지 않는 배치 코드 경로) — 따라서 회차당 최대 처리 건수({@link #getMaxBatchSize()})가 국세청
 * 서비스키 일일 쿼터를 지키는 <b>유일한</b> 통제 수단이다. {@code verifyRealtime}은 계속사업자(01) 확정
 * 시 상태조회+대조 2회 호출을 소모하므로, 회차당 최악 국세청 호출량은 {@code maxBatchSize * 2}다.
 */
@ConfigurationProperties(prefix = "biz-verify.pending-reverify")
public class PendingBusinessReverifyProperties {

    /**
     * 운영 중 즉시 끌 수 있는 킬스위치. false면 스케줄러가 트리거돼도 국세청 호출을 전혀 하지 않고
     * 즉시 반환한다(대상 조회조차 하지 않음).
     */
    private boolean enabled = true;

    /**
     * 회차당 최대 처리 건수. 기본 20 — verifyRealtime 최악 호출량(계속사업자 확정 시 회사당 2회) 기준
     * 회차당 최대 40회로, 실 가입/실시간 API 트래픽과 공유하는 일일 쿼터 여유
     * ({@link BizVerifyProperties.RateLimit#getDailyLimit()}=300)를 크게 잠식하지 않도록 보수적으로
     * 잡았다. 장애로 PENDING 적체가 커져도 한 회차에 몰아 처리하지 않고 여러 회차(cron 주기)에 걸쳐
     * 점진적으로 소진한다.
     */
    private int maxBatchSize = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }
}
