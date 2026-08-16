package com.hajacheck.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 데모 리셋 스케줄러(#1626) 설정 — {@code PlanExpiryProperties} 패턴(기동 스위치 기본 false + cron
 * 프로퍼티 외부화 + 1회 상한 안전장치)을 따른다. cron 자체는 {@code DemoResetScheduler} 의
 * {@code @Scheduled(cron = "${hajacheck.demo.reset.cron:0 10 4 * * *}")} 플레이스홀더로 바인딩된다
 * (기본 매일 04:10 Asia/Seoul — 구독 만료 배치 04:00·점검 알림 06:00 과 겹치지 않게).
 */
@ConfigurationProperties(prefix = "hajacheck.demo.reset")
public class DemoResetProperties {

    /** 기동 스위치. 기본 false — 켜지 않으면 대상 조회조차 하지 않는다. */
    private boolean enabled = false;

    /**
     * 리셋 1회가 감당할 데모 회사 시설물 수 상한 — 초과하면 <b>아무것도 지우지 않고</b> ERROR 로 중단한다
     * ({@code PlanExpiryProperties#getMaxPerRun} 과 동일 취지). 데모 회사에 시설물이 이 수를 넘겨 있다는
     * 것은 정상 데모 사용이 아니라 설정 오류(데모 loginId 가 실사용 계정을 가리킴)나 대량 오염 사고의
     * 신호다 — destructive 배치는 사고 신호 앞에서 멈추는 쪽이 안전하다.
     */
    private int maxFacilitiesPerReset = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxFacilitiesPerReset() {
        return maxFacilitiesPerReset;
    }

    public void setMaxFacilitiesPerReset(int maxFacilitiesPerReset) {
        this.maxFacilitiesPerReset = maxFacilitiesPerReset;
    }
}
