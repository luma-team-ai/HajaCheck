package com.hajacheck.core.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 고아 보고서 PDF 정리 배치(#1653 P3) 유예기간 설정(#1680) — SpringBoot_코드_컨벤션.md §9(매직넘버 금지 →
 * @ConfigurationProperties). DemoResetProperties/PlanExpiryProperties 패턴과 동일하게 스케줄러 전용
 * 프로퍼티 클래스를 별도로 둔다(ReportPdfStorageProperties는 저장 경로·용량 관심사라 분리).
 */
@ConfigurationProperties(prefix = "hajacheck.report.pdf-cleanup")
public class ReportPdfCleanupProperties {

    /**
     * 업로드 후 finalize까지 기다려주는 유예기간(일). 기본 7일 — 확정 직전 잠깐 재업로드하는 정상
     * 흐름을 오탐하지 않도록(짧은 임계는 정상 작업 중인 파일까지 지울 위험이 있다).
     */
    private int retentionDays = 7;

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}
