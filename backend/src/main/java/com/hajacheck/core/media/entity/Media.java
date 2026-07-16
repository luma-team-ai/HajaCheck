package com.hajacheck.core.media.entity;

import com.hajacheck.core.inspection.entity.Inspection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 점검 과정에서 업로드하거나 영상에서 추출한 이미지·영상 메타데이터. */
@Entity
@Getter
@Table(name = "media", indexes = {
        @Index(name = "idx_media_inspection", columnList = "inspection_id")
})
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Media {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inspection_id", nullable = false)
    private Long inspectionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", insertable = false, updatable = false)
    private Inspection inspection;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "file_type", columnDefinition = "media_file_type", nullable = false)
    private MediaFileType fileType;

    @Column(name = "original_url", nullable = false, length = 500)
    private String originalUrl;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    /** 개념상 원본 {@code media.id}지만 최신 DDL에는 FK 제약이 없으므로 식별자 값으로 유지한다. */
    @Column(name = "source_video_id")
    private Long sourceVideoId;

    @Column(name = "frame_index")
    private Integer frameIndex;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "gps_lat", precision = 9, scale = 6)
    private BigDecimal gpsLat;

    @Column(name = "gps_lng", precision = 9, scale = 6)
    private BigDecimal gpsLng;

    @Column(name = "mime_signature_verified", nullable = false)
    private boolean mimeSignatureVerified;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Builder(access = AccessLevel.PRIVATE)
    private Media(Long inspectionId, MediaFileType fileType, String originalUrl,
                  String thumbnailUrl, Long sourceVideoId, Integer frameIndex,
                  Instant capturedAt, BigDecimal gpsLat, BigDecimal gpsLng,
                  boolean mimeSignatureVerified, String mimeType) {
        this.inspectionId = inspectionId;
        this.fileType = fileType;
        this.originalUrl = originalUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.sourceVideoId = sourceVideoId;
        this.frameIndex = frameIndex;
        this.capturedAt = capturedAt;
        this.gpsLat = gpsLat;
        this.gpsLng = gpsLng;
        this.mimeSignatureVerified = mimeSignatureVerified;
        this.mimeType = mimeType;
    }

    public static Media create(Long inspectionId, MediaFileType fileType, String originalUrl,
                               String thumbnailUrl, Instant capturedAt,
                               BigDecimal gpsLat, BigDecimal gpsLng,
                               boolean mimeSignatureVerified, String mimeType) {
        return Media.builder()
                .inspectionId(inspectionId)
                .fileType(fileType)
                .originalUrl(originalUrl)
                .thumbnailUrl(thumbnailUrl)
                .capturedAt(capturedAt)
                .gpsLat(gpsLat)
                .gpsLng(gpsLng)
                .mimeSignatureVerified(mimeSignatureVerified)
                .mimeType(mimeType)
                .build();
    }

    public static Media extractedFrame(Long inspectionId, String originalUrl, String thumbnailUrl,
                                       Long sourceVideoId, Integer frameIndex, Instant capturedAt,
                                       BigDecimal gpsLat, BigDecimal gpsLng,
                                       boolean mimeSignatureVerified, String mimeType) {
        return Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl(originalUrl)
                .thumbnailUrl(thumbnailUrl)
                .sourceVideoId(sourceVideoId)
                .frameIndex(frameIndex)
                .capturedAt(capturedAt)
                .gpsLat(gpsLat)
                .gpsLng(gpsLng)
                .mimeSignatureVerified(mimeSignatureVerified)
                .mimeType(mimeType)
                .build();
    }

    public void markMimeSignatureVerified() {
        this.mimeSignatureVerified = true;
    }
}
