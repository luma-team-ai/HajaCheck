package com.hajacheck.core.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 보고서 PDF 저장 설정 — SpringBoot_코드_컨벤션.md §9(매직넘버 금지 → @ConfigurationProperties).
 * FileStorageProperties(사업자등록증)/MediaUploadProperties(점검 미디어)와 허용 타입·용량 기준이 달라
 * 별도 프로퍼티로 분리한다.
 */
@ConfigurationProperties(prefix = "app.report-pdf-storage")
public class ReportPdfStorageProperties {

    /** PDF 저장 루트 디렉터리(절대경로). */
    private String baseDir = System.getProperty("java.io.tmpdir") + "/hajacheck-report-pdf";

    /** 개별 파일 최대 용량(bytes). 기본 20MB(보고서 PDF는 이미지·표가 많아 사업자등록증보다 큼). */
    private long maxSizeBytes = 20_971_520L;

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }
}
