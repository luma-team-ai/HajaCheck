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

    /** 한 번의 업로드 요청에서 허용하는 최대 파일 개수 — 요청 총 바이트 상한(max-request-size)과는
     * 별개로, uploadMedia()가 파일마다 동기로 수행하는 매직바이트 검증+EXIF 추출+썸네일·상세이미지
     * 인코딩(이미지 디코딩/인코딩 다회)이 Tomcat 워커 스레드를 점유하는 시간 자체를 통제하는
     * 방어값이다 — 소용량 파일을 대량으로 담으면 총 바이트 상한만으로는 처리 시간이 막히지 않는다
     * (PR머신 리뷰 P1, #1067). 기존 10장 제한이 실사용에 너무 빡빡하다는 요청에 맞춰 50장으로 완화. */
    private int maxFilesPerRequest = 50;

    /** 썸네일 재인코딩 시 가로/세로 중 긴 변의 최대 픽셀(비율 유지 축소). */
    private int thumbnailMaxDimension = 400;

    /** 상세뷰(분석 결과 뷰어) 재인코딩 시 가로/세로 중 긴 변의 최대 픽셀 — 그리드용 썸네일보다
     * 커야 하자(크랙 폭 등)를 육안으로 판별할 수 있다(#788). */
    private int detailMaxDimension = 1600;

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

    public int getDetailMaxDimension() {
        return detailMaxDimension;
    }

    public void setDetailMaxDimension(int detailMaxDimension) {
        this.detailMaxDimension = detailMaxDimension;
    }
}
