package com.hajacheck.core.media.dto;

import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 미디어 응답 DTO — Entity 직접 노출 금지(§0). originalUrl 필드는 의도적으로 없음(PRD FR-2 "원본은
 * 직접 서빙하지 않는다" — 저장 경로 자체를 클라이언트에 노출하지 않는다). thumbnailUrl 도 내부 저장 경로가
 * 아니라 인가된 API 경로(/api/media/{id}/thumbnail)를 담는다.
 */
public record MediaResponse(
        Long id,
        Long inspectionId,
        MediaFileType fileType,
        String thumbnailUrl,
        String mimeType,
        LocalDateTime capturedAt,
        BigDecimal gpsLat,
        BigDecimal gpsLng,
        LocalDateTime createdAt
) {
    public static MediaResponse from(Media media) {
        return new MediaResponse(
                media.getId(),
                media.getInspectionId(),
                media.getFileType(),
                "/api/media/" + media.getId() + "/thumbnail",
                media.getMimeType(),
                media.getCapturedAt(),
                media.getGpsLat(),
                media.getGpsLng(),
                media.getCreatedAt()
        );
    }
}
