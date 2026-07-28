package com.hajacheck.membership.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 구독 결제 주기 만료 → FREE 자동 강등 배치({@code PlanExpiryScheduler}) 설정(#1145 / HAJA-549) —
 * SpringBoot_코드_컨벤션.md §9: 매직넘버 금지.
 *
 * <p>⚠️ <b>이 세 값은 "설정"이 아니라 안전장치다.</b> 이 배치는 사람 개입 없이 유료 권한을 내리고
 * 좌석을 정지시킨다. 되돌리려면 수동 복구가 필요하고 그 사이 사용자는 기능을 잃는다.
 *
 * <p><b>왜 기본값이 비활성인가(#1145 §2-1)</b>: V27 백필은 {@code current_period_end} 를 실제 결제일이
 * 아니라 {@code started_at + 1개월} 이라는 <b>파생 추정치</b>로 채웠다. 그래서 가입한 지 한 달이 넘은
 * 유료 구독은 이미 전부 "만료" 상태로 DB에 들어 있다. 아무 가드 없이 배포하면 첫 실행에서 기존 유료
 * 회사가 일괄 FREE로 강등된다. 운영자가 프리플라이트(prod 강등 대상 행 수 확인 + 실제 결제 이력
 * 대조)를 마친 뒤에만 {@code enabled=true} 로 켠다.
 */
@ConfigurationProperties(prefix = "hajacheck.plan.expiry")
public class PlanExpiryProperties {

    /**
     * 기동 스위치 — <b>기본 false(비활성)</b>. false면 스케줄러가 트리거돼도 대상 조회조차 하지 않고
     * 즉시 반환한다(클래스 javadoc의 소급 대량 강등 위험 참고).
     */
    private boolean enabled = false;

    /**
     * 유예 기간 — 기본 {@code P0D}(만료 즉시 강등). 판정식은
     * {@code current_period_end < now - gracePeriod} 다.
     *
     * <p>0일이 기본인 이유: 빌링키(정기결제) 수단이 없어 만료 시점에 자동 청구를 할 수 없으므로,
     * 유예를 둬도 그 기간에 결제를 유도할 방법이 없다. 정책이 바뀔 수 있으니 설정으로만 빼 둔다
     * (#1145 §2 확정 결정 — 임의 변경 금지).
     */
    private Duration gracePeriod = Duration.ZERO;

    /**
     * 1회 실행당 강등 상한 — 기본 50. <b>초과하면 아무것도 하지 않고 중단</b>한다(부분 강등도 만들지
     * 않는다). 상한을 넘는 대량 강등은 정상 운영이 아니라 사고 신호({@code current_period_end} 백필
     * 오염·시각 판정 오류 등)이므로, "일부만 처리"보다 "전부 멈추고 사람을 부른다"가 안전하다.
     */
    private int maxPerRun = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getGracePeriod() {
        return gracePeriod;
    }

    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    public int getMaxPerRun() {
        return maxPerRun;
    }

    public void setMaxPerRun(int maxPerRun) {
        this.maxPerRun = maxPerRun;
    }
}
