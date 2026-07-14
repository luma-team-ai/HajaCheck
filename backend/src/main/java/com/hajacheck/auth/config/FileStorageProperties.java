package com.hajacheck.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사업자등록증 파일 저장 설정 — SpringBoot_코드_컨벤션.md §9(매직넘버 금지 → @ConfigurationProperties).
 * base-dir 는 프로파일별로 다르다(local=임시경로, docker/dev=마운트 볼륨 경로 — application-docker.yml).
 * 시크릿 아님.
 */
@ConfigurationProperties(prefix = "app.file-storage")
public class FileStorageProperties {

    /** 파일 저장 루트 디렉터리(절대경로). */
    private String baseDir = System.getProperty("java.io.tmpdir") + "/hajacheck-files";

    /** 저장 파일 조회 URL 접두(정적 리소스 매핑 대상). */
    private String baseUrlPath = "/files";

    /** 허용 MIME 화이트리스트. */
    private List<String> allowedContentTypes = List.of("image/jpeg", "image/png", "application/pdf");

    /** 개별 파일 최대 용량(bytes). 기본 10MB. */
    private long maxSizeBytes = 10_485_760L;

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public String getBaseUrlPath() {
        return baseUrlPath;
    }

    public void setBaseUrlPath(String baseUrlPath) {
        this.baseUrlPath = baseUrlPath;
    }

    public List<String> getAllowedContentTypes() {
        return allowedContentTypes;
    }

    public void setAllowedContentTypes(List<String> allowedContentTypes) {
        this.allowedContentTypes = allowedContentTypes;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }
}
