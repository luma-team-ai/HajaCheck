package com.hajacheck.core.facility.entity;

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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 시설물 등록 시 첨부하는 대표 사진(최대 4장) — DDL facility_photos 테이블 대응(#628 / HAJA-347).
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지. {@code facilityId} 는 FK 값 컬럼을 실제 매핑 소스로 두고,
 * 지연 로딩 연관관계({@code facility})는 조회 전용({@code insertable/updatable = false})으로 병행 제공한다
 * (Inspection.facility 와 동일 패턴).
 *
 * <p>⚠️ BaseTimeEntity 상속 금지: facility_photos 테이블에는 updated_at 컬럼이 없다(created_at 만 존재,
 * Media/Inspection과 동일한 이유 — 사진 행은 수정하지 않고 교체만 한다).
 */
@Entity
@Getter
@Table(
        name = "facility_photos",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_facility_photos_facility_sort",
                columnNames = {"facility_id", "sort_order"}),
        indexes = @Index(name = "idx_facility_photos_facility", columnList = "facility_id"))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FacilityPhoto {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", insertable = false, updatable = false)
    private Facility facility;

    @Column(name = "photo_url", nullable = false, length = 500)
    private String photoUrl;

    // DDL check(sort_order between 0 and 3) — 시설물당 최대 4장(0~3 인덱스).
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private FacilityPhoto(Long facilityId, String photoUrl, Integer sortOrder) {
        this.facilityId = facilityId;
        this.photoUrl = photoUrl;
        this.sortOrder = sortOrder;
    }
}
