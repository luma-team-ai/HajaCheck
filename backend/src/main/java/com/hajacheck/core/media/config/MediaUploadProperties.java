package com.hajacheck.core.media.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 점검 미디어(사진) 업로드 설정 — SpringBoot_코드_컨벤션.md §9(매직넘버 금지 → @ConfigurationProperties).
 * 사업자등록증(FileStorageProperties)과 허용 타입·용량 기준이 달라 별도 프로퍼티로 분리한다
 * (이번 PR 범위는 이미지만 — 영상은 후속 PR에서 추가 예정).
 */
@ConfigurationProperties(prefix = "app.media-upload")
public class MediaUploadProperties {

    /** 허용 MIME 화이트리스트(이미지만 — JPG/PNG). */
    private List<String> allowedContentTypes = List.of("image/jpeg", "image/png");

    /** 개별 파일 최대 용량(bytes). 기본 20MB(폰 카메라 사진 고려, 사업자등록증보다 큼). */
    private long maxSizeBytes = 20_971_520L;

    /** 한 번의 업로드 요청에서 허용하는 최대 파일 개수. */
    private int maxFilesPerRequest = 20;

    /** 썸네일 재인코딩 시 가로/세로 중 긴 변의 최대 픽셀(비율 유지 축소). */
    private int thumbnailMaxDimension = 400;

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

    public int getMaxFilesPerRequest() {
        return maxFilesPerRequest;
    }

    public void setMaxFilesPerRequest(int maxFilesPerRequest) {
        this.maxFilesPerRequest = maxFilesPerRequest;
    }

    public int getThumbnailMaxDimension() {
        return thumbnailMaxDimension;
    }

    public void setThumbnailMaxDimension(int thumbnailMaxDimension) {
        this.thumbnailMaxDimension = thumbnailMaxDimension;
    }
}
