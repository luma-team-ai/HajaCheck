package com.hajacheck.core.media.entity;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.media.support.CapturedAtConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 점검 과정에서 등록·추출한 이미지/영상 — DDL media 테이블 대응(dev-05-03).
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지, 연관관계 대신 FK 값 컬럼만 보유(inspectionId).
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: media 테이블에는 updated_at 컬럼이 없다(created_at 만 존재).
 * fileType 은 PG named enum(media_file_type) — @JdbcTypeCode(NAMED_ENUM) 매핑.
 *
 * <p>originalUrl/thumbnailUrl 은 정적으로 서빙되는 실제 URL이 아니라 {@link com.hajacheck.auth.support
 * .FileStorageService}의 저장키(storageKey)를 담는다 — 어떤 파일도 정적 경로로 직접 서빙하지 않고
 * 인가된 컨트롤러 엔드포인트를 통해서만 읽으므로(PRD FR-2: "원본은 직접 서빙하지 않고 서버 측 재인코딩본만
 * 제공"), 컬럼에 "진짜 URL"을 둘 필요가 없다. 어떤 API 응답도 이 값을 그대로 반환하지 않는다
 * (MediaResponse 는 별도로 /api/media/{id}/thumbnail 경로를 조립해서 내려준다).
 * mimeSignatureVerified 는 항상 true 로만 저장된다 — 매직바이트 검증에 실패한 파일은 애초에 이 엔티티가
 * 만들어지지 않는다(MediaService 에서 저장 전에 걸러짐).
 *
 * <p>sourceVideoId/frameIndex 는 영상 프레임 추출(후속 PR) 을 위해 스키마에 이미 있는 컬럼 — 이번 PR에서
 * 생성하는 모든 행은 IMAGE 이므로 항상 null.
 */
@Entity
@Getter
@Table(name = "media")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Media {

    // id: PG generated always as identity → IDENTITY 전략
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

    /** 업로드가 완료된 내부 저장소 객체 위치. 서버가 임의 외부 URL을 fetch하는 입력으로 사용하지 않는다. */
    @Column(name = "original_url", nullable = false, length = 500)
    private String originalUrl;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(name = "source_video_id")
    private Long sourceVideoId;

    @Column(name = "frame_index")
    private Integer frameIndex;

    // 카메라 현지시각(naive) ↔ timestamptz 컬럼 변환을 서버 TZ와 무관하게 고정(리뷰 P2) — 상세 이유는
    // CapturedAtConverter 참조.
    @Convert(converter = CapturedAtConverter.class)
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "gps_lat", precision = 9, scale = 6)
    private BigDecimal gpsLat;

    @Column(name = "gps_lng", precision = 9, scale = 6)
    private BigDecimal gpsLng;

    /** 새 엔티티는 항상 false로 시작하며, 검증 결과를 호출자 인자로 받지 않는다. */
    @Column(name = "mime_signature_verified", nullable = false)
    private boolean mimeSignatureVerified;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Builder
    private Media(Long inspectionId, MediaFileType fileType, String originalUrl, String thumbnailUrl,
                  Long sourceVideoId, Integer frameIndex, LocalDateTime capturedAt,
                  BigDecimal gpsLat, BigDecimal gpsLng, boolean mimeSignatureVerified, String mimeType) {
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
}
