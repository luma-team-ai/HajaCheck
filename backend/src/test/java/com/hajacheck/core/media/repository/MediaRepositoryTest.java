package com.hajacheck.core.media.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(media) 대조를 위해 Testcontainers PostgreSQL 사용(dev-05-03).
// users → facilities → inspections → media 순으로 FK 를 충족하며 시드한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class MediaRepositoryTest extends PostgresTestSupport {

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private FacilityRepository facilityRepository;

    @Autowired
    private TestEntityManager em;

    // 시설물 대표 사진(#632/#652) 테스트용 — user→company→facility 까지만 시드하고 facility_id 를 돌려준다
    // (inspection 없이 facility 전용 media 로우를 검증하기 위함). 이메일은 seedInspection() 과 겹치지 않게 한다.
    private Long seedFacility() {
        return seedFacility("");
    }

    // HAJA-367 교차오염 테스트처럼 한 테스트 안에서 시설물을 여러 개 시드해야 할 때 이메일 충돌을
    // 피하기 위한 접미사 파라미터 오버로드 — 기존 무인자 호출부는 seedFacility() 그대로 유지.
    private Long seedFacility(String emailSuffix) {
        User owner = User.builder()
                .email("owner-fac" + emailSuffix + "@haja.com")
                .name("시설소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build();
        em.persist(owner);
        em.flush();

        Company company = Company.createPendingReview(
                owner.getId(), "시설사진테스트회사", "REG-FAC-" + owner.getId(), "대표자",
                "서울시", null, "https://files.example/business.pdf", "{}");
        em.persist(company);
        em.flush();
        company.markBusinessVerified();
        company.approve(owner.getId());
        em.flush();

        em.persist(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        em.persist(owner);
        em.flush();

        Facility facility = Facility.builder().companyId(company.getId()).name("대표사진빌딩").type("BUILDING").build();
        em.persist(facility);
        em.flush();

        return facility.getId();
    }

    private Long seedInspection() {
        // HAJA-25 배정 검증 트리거: created_by·assigned_inspector 는 승인+검증된 회사의 유효한 APPROVED
        // 멤버여야 하고 담당자는 INSPECTOR/ADMIN 역할이어야 한다. owner 를 두 역할로 함께 재사용하므로
        // INSPECTOR 역할 + 승인/검증 회사 + approvedOwner 멤버십 + company_id 를 함께 시드한다.
        User owner = User.builder()
                .email("owner-a@haja.com")
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build();
        em.persist(owner);
        em.flush();

        Company company = Company.createPendingReview(
                owner.getId(), "미디어테스트회사", "REG-" + owner.getId(), "대표자",
                "서울시", null, "https://files.example/business.pdf", "{}");
        em.persist(company);
        em.flush();
        company.markBusinessVerified();
        company.approve(owner.getId());
        em.flush();

        em.persist(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        em.persist(owner);
        em.flush();

        Facility facility = Facility.builder().companyId(company.getId()).name("테스트빌딩").type("BUILDING").build();
        em.persist(facility);
        em.flush();

        Inspection inspection = Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(owner.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(InspectionStatus.CREATED)
                .build();
        em.persist(inspection);
        em.flush();

        return inspection.getId();
    }

    @Test
    void save_저장후_createdAt과id채워짐_필수컬럼만() {
        Long inspectionId = seedInspection();
        Media media = Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/a.png")
                .mimeSignatureVerified(true)
                .build();

        Media saved = mediaRepository.save(media);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getFileType()).isEqualTo(MediaFileType.IMAGE);
        assertThat(saved.isMimeSignatureVerified()).isTrue();
        assertThat(saved.getThumbnailUrl()).isNull();
        assertThat(saved.getGpsLat()).isNull();
    }

    @Test
    void save_전체컬럼채움_정상저장() {
        Long inspectionId = seedInspection();
        Media media = Media.builder()
                .inspectionId(inspectionId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/a.png")
                .thumbnailUrl("inspection-media-thumb/a.jpg")
                .capturedAt(LocalDateTime.of(2026, 7, 1, 12, 0))
                .gpsLat(new BigDecimal("37.500000"))
                .gpsLng(new BigDecimal("127.000000"))
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();

        Media saved = mediaRepository.save(media);

        assertThat(saved.getThumbnailUrl()).isEqualTo("inspection-media-thumb/a.jpg");
        assertThat(saved.getCapturedAt()).isEqualTo(LocalDateTime.of(2026, 7, 1, 12, 0));
        assertThat(saved.getGpsLat()).isEqualByComparingTo("37.500000");
        assertThat(saved.getGpsLng()).isEqualByComparingTo("127.000000");
        assertThat(saved.getMimeType()).isEqualTo("image/png");
    }

    /**
     * captured_at 은 DDL상 timestamptz 인데 엔티티 필드는 naive LocalDateTime(카메라 현지시각)이다
     * (리뷰 P2). CapturedAtConverter 가 서버 TZ와 무관하게 고정 존(Asia/Seoul)으로 변환하는지
     * 실제 PG 라운드트립으로 검증한다 — 저장 시점과 조회 시점의 JVM 기본 TZ 를 서로 다르게 바꿔도
     * 원문 벽시계 값이 그대로 복원되어야 한다.
     */
    @Test
    void save_저장과조회사이TZ변경돼도_capturedAt은원문벽시계값유지() {
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            Long inspectionId = seedInspection();
            LocalDateTime raw = LocalDateTime.of(2024, 3, 15, 14, 30, 0);

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Media media = Media.builder()
                    .inspectionId(inspectionId)
                    .fileType(MediaFileType.IMAGE)
                    .originalUrl("inspection-media/a.png")
                    .capturedAt(raw)
                    .mimeSignatureVerified(true)
                    .build();
            Media saved = mediaRepository.save(media);
            em.flush();
            em.clear();

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Media reloaded = mediaRepository.findById(saved.getId()).orElseThrow();

            assertThat(reloaded.getCapturedAt()).isEqualTo(raw);
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }

    // ── 시설물 대표 사진(#632/#652, HAJA-377) ──────────────────────────────────────────────

    @Test
    void save_facility전용로우_inspectionId없이_facilityId만저장() {
        Long facilityId = seedFacility();
        Media media = Media.builder()
                .facilityId(facilityId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/a.png")
                .mimeSignatureVerified(true)
                .build();

        Media saved = mediaRepository.save(media);
        em.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getFacilityId()).isEqualTo(facilityId);
        assertThat(saved.getInspectionId()).isNull();
    }

    @Test
    void countByFacilityId와_findByFacilityIdOrderByIdAsc_해당시설물사진만반환() {
        Long facilityId = seedFacility();
        Media first = mediaRepository.save(Media.builder()
                .facilityId(facilityId).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/1.png").mimeSignatureVerified(true).build());
        Media second = mediaRepository.save(Media.builder()
                .facilityId(facilityId).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/2.png").mimeSignatureVerified(true).build());
        em.flush();

        assertThat(mediaRepository.countByFacilityId(facilityId)).isEqualTo(2L);
        assertThat(mediaRepository.findByFacilityIdOrderByIdAsc(facilityId))
                .extracting(Media::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void save_inspectionId와facilityId_둘다null이면_XOR_CHECK제약위반() {
        // chk_media_inspection_xor_facility — 둘 다 비면 DB 레벨에서 거부되어야 한다(오염 로우 차단).
        Media media = Media.builder()
                .fileType(MediaFileType.IMAGE)
                .originalUrl("orphan/a.png")
                .mimeSignatureVerified(true)
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mediaRepository.saveAndFlush(media))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void save_inspectionId와facilityId_둘다채우면_XOR_CHECK제약위반() {
        // chk_media_inspection_xor_facility — 둘 다 채워도 DB 레벨에서 거부되어야 한다(정확히 하나만 허용).
        // 두 FK(inspection_id, facility_id) 모두 실재 로우를 참조해야 CHECK 위반만 격리 검증된다.
        Long inspectionId = seedInspection();
        Long facilityId = seedFacility();

        Media media = Media.builder()
                .inspectionId(inspectionId)
                .facilityId(facilityId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("both/a.png")
                .mimeSignatureVerified(true)
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mediaRepository.saveAndFlush(media))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * #1024 — code-reviewer P2 반영. 위 "media 먼저 삭제 후 facility 삭제하면 성공" 테스트만으로는
     * media 로우를 먼저 지우지 않고 바로 facility를 지워도 어차피 성공했을 가능성을 배제하지 못한다
     * (즉 fk_media_facility 가 실제로 존재/작동하는지를 증명하지 않는다). 이 테스트가 그 반대 경로 —
     * 정리 없이 바로 delete()하면 실 PG가 진짜로 FK 위반을 던지는지 — 를 고정해 회귀의 실체를 증명한다.
     */
    @Test
    void 대표사진있는시설물_media정리없이facility바로삭제하면FK위반발생() {
        Long facilityId = seedFacility();
        mediaRepository.save(Media.builder()
                .facilityId(facilityId).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/1.png").mimeSignatureVerified(true).build());
        em.flush();
        em.clear();

        Facility facility = em.find(Facility.class, facilityId);

        assertThat(mediaRepository.findByFacilityIdOrderByIdAsc(facilityId)).hasSize(1);
        // em.flush()(raw JPA)는 Spring 예외 변환 프록시를 거치지 않아 Hibernate 원시 예외가 그대로
        // 튀어나온다 — repository.flush()(Spring Data JpaRepository)를 써야 다른 CHECK 제약 테스트
        // (saveAndFlush)와 동일하게 DataIntegrityViolationException으로 변환된다.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            facilityRepository.delete(facility);
            facilityRepository.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * #1024 — V19(#1017)의 fk_media_facility(NO ACTION)는 대표 사진이 남은 시설물을 그냥 delete()하면
     * FK 위반을 낸다. 이 테스트는 이 시나리오 자체가 이전까지 전혀 검증되지 않았음을 메우는 회귀 방지용
     * — FacilityService.delete()가 실제로 하는 순서(media 로우 먼저 삭제 → 시설물 삭제)를 실 PG 로
     * 재현해 FK 위반 없이 성공하는지 확인한다.
     */
    @Test
    void 대표사진있는시설물_media먼저삭제후facility삭제하면FK위반없이성공() {
        Long facilityId = seedFacility();
        mediaRepository.save(Media.builder()
                .facilityId(facilityId).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/1.png").mimeSignatureVerified(true).build());
        mediaRepository.save(Media.builder()
                .facilityId(facilityId).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/2.png").mimeSignatureVerified(true).build());
        em.flush();
        em.clear();

        List<Media> facilityMedia = mediaRepository.findByFacilityIdOrderByIdAsc(facilityId);
        assertThat(facilityMedia).hasSize(2);
        mediaRepository.deleteAll(facilityMedia);
        em.flush();

        Facility facility = em.find(Facility.class, facilityId);
        facilityRepository.delete(facility);
        em.flush();

        assertThat(em.find(Facility.class, facilityId)).isNull();
        assertThat(mediaRepository.findByFacilityIdOrderByIdAsc(facilityId)).isEmpty();
    }

    // ── 시설물 목록 썸네일 배치 조회(HAJA-367/#670) ──────────────────────────────────────────

    // 리포지토리는 대상 시설물의 사진 전체를 (facilityId asc, id asc)로 정렬해 반환한다 — "시설물당
    // 첫 값(최초 등록 사진)만 채택"은 DefectRepository.findLatestByFacilityIds와 동일하게 서비스 계층
    // (FacilityService.list())의 Collectors.toMap 조립 책임이다. 이 테스트는 정렬·필터가 시설물별로
    // 교차오염 없이 정확한지만 검증한다(같은 회사 소속 시설물 2건).
    @Test
    void findFirstIdsByFacilityIds_시설물별로필터링되고id오름차순정렬_교차오염없음() {
        Long facilityA = seedFacility();
        Long companyId = em.find(Facility.class, facilityA).getCompanyId();
        Facility facilityBEntity =
                Facility.builder().companyId(companyId).name("두번째빌딩").type("BUILDING").build();
        em.persist(facilityBEntity);
        em.flush();
        Long facilityB = facilityBEntity.getId();

        Media firstOfA = mediaRepository.save(Media.builder()
                .facilityId(facilityA).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/a1.png").mimeSignatureVerified(true).build());
        Media secondOfA = mediaRepository.save(Media.builder()
                .facilityId(facilityA).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/a2.png").mimeSignatureVerified(true).build());
        Media firstOfB = mediaRepository.save(Media.builder()
                .facilityId(facilityB).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/b1.png").mimeSignatureVerified(true).build());
        em.flush();

        List<FacilityRepresentativeMediaProjection> result =
                mediaRepository.findFirstIdsByFacilityIds(List.of(facilityA, facilityB), companyId);

        assertThat(result)
                .filteredOn(p -> p.getFacilityId().equals(facilityA))
                .extracting(FacilityRepresentativeMediaProjection::getMediaId)
                .containsExactly(firstOfA.getId(), secondOfA.getId());
        assertThat(result)
                .filteredOn(p -> p.getFacilityId().equals(facilityB))
                .extracting(FacilityRepresentativeMediaProjection::getMediaId)
                .containsExactly(firstOfB.getId());
    }

    @Test
    void findFirstIdsByFacilityIds_사진없는시설물은결과행자체가없음() {
        Long facilityId = seedFacility();
        Long companyId = em.find(Facility.class, facilityId).getCompanyId();

        List<FacilityRepresentativeMediaProjection> result =
                mediaRepository.findFirstIdsByFacilityIds(List.of(facilityId), companyId);

        assertThat(result).isEmpty();
    }

    // code-reviewer P2 — 리포지토리 계층에서도 companyId 로 방어적 재검증(DefectRepository.
    // findLatestByFacilityIds 와 동일 원칙). 타 회사 시설물 id 가 facilityIds 에 섞여 들어와도
    // companyId 불일치로 결과에서 제외되어야 한다.
    @Test
    void findFirstIdsByFacilityIds_다른회사시설물은companyId불일치로제외() {
        Long myFacility = seedFacility("-mine");
        Long myCompanyId = em.find(Facility.class, myFacility).getCompanyId();
        Long otherFacility = seedFacility("-other");
        mediaRepository.save(Media.builder()
                .facilityId(myFacility).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/mine.png").mimeSignatureVerified(true).build());
        mediaRepository.save(Media.builder()
                .facilityId(otherFacility).fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/other.png").mimeSignatureVerified(true).build());
        em.flush();

        List<FacilityRepresentativeMediaProjection> result =
                mediaRepository.findFirstIdsByFacilityIds(List.of(myFacility, otherFacility), myCompanyId);

        assertThat(result)
                .extracting(FacilityRepresentativeMediaProjection::getFacilityId)
                .containsExactly(myFacility);
    }

    @Test
    void findFirstIdsByInspectionIds_점검별첫사진폴백용으로시설물ID와미디어ID를반환() {
        Long inspectionId = seedInspection();
        Long facilityId = em.find(Inspection.class, inspectionId).getFacilityId();
        Media first = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/1.png").mimeSignatureVerified(true).build());
        Media second = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/2.png").mimeSignatureVerified(true).build());
        em.flush();

        List<FacilityRepresentativeMediaProjection> result =
                mediaRepository.findFirstIdsByInspectionIds(List.of(inspectionId));

        assertThat(result)
                .extracting(FacilityRepresentativeMediaProjection::getFacilityId)
                .containsExactly(facilityId, facilityId);
        assertThat(result)
                .extracting(FacilityRepresentativeMediaProjection::getMediaId)
                .containsExactly(first.getId(), second.getId());
    }

    // ── purpose 필터(#1641 P2 — 시설물 상세 "점검 이력" 사진 수 배지/미리보기에서 조치 후 사진 제외) ──

    @Test
    void countGroupByInspectionIdsAndPurpose_조치후사진은세지않는다() {
        Long inspectionId = seedInspection();
        mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/source.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/action.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.DEFECT_ACTION).build());
        em.flush();

        List<MediaRepository.InspectionMediaCountProjection> result = mediaRepository
                .countGroupByInspectionIdsAndPurpose(List.of(inspectionId), MediaPurpose.INSPECTION_SOURCE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getInspectionId()).isEqualTo(inspectionId);
        assertThat(result.get(0).getCnt()).isEqualTo(1L); // 원본 1장만 — 조치 사진은 제외.
    }

    @Test
    void countGroupByInspectionIdsAndPurpose_원본만있으면기존카운트와동일() {
        // 회귀 방지 — purpose 필터가 원본 촬영사진 집계 자체를 실수로 줄이지 않는지 확인.
        Long inspectionId = seedInspection();
        mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/a.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/b.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        em.flush();

        List<MediaRepository.InspectionMediaCountProjection> result = mediaRepository
                .countGroupByInspectionIdsAndPurpose(List.of(inspectionId), MediaPurpose.INSPECTION_SOURCE);

        assertThat(result.get(0).getCnt()).isEqualTo(2L);
    }

    @Test
    void findTop2ByInspectionIdAndPurposeOrderByIdAsc_조치후사진은제외하고원본만id오름차순반환() {
        Long inspectionId = seedInspection();
        // 조치 후 사진을 가장 먼저 저장해 가장 작은 id를 갖게 한다 — purpose 필터가 실제로 걸리지 않으면
        // id asc 정렬상 이 사진이 top2에 끼어드는 회귀를 잡아낸다(가장 강한 반증 시나리오).
        Media action = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/action.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.DEFECT_ACTION).build());
        Media source1 = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/source1.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        Media source2 = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/source2.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        Media source3 = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/source3.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        em.flush();

        List<Media> result = mediaRepository.findTop2ByInspectionIdAndPurposeOrderByIdAsc(
                inspectionId, MediaPurpose.INSPECTION_SOURCE);

        assertThat(result).extracting(Media::getId).containsExactly(source1.getId(), source2.getId());
        assertThat(result).noneMatch(m -> m.getId().equals(action.getId()));
        assertThat(result).noneMatch(m -> m.getId().equals(source3.getId())); // 상한 2건 고정도 함께 확인.
    }

    // ── 증분 분석 대상 조회(V42, #1654) ──────────────────────────────────────────────

    @Test
    void findByInspectionIdAndFileTypeAndPurposeAndAnalyzedAtIsNullOrderByIdAsc_미분석원본사진만_id오름차순반환() {
        // InspectionAnalysisService.startAnalysis가 그대로 쓰는 쿼리 — 이미 분석된 원본 사진(analyzed_at
        // 존재)과 조치 후 사진(DEFECT_ACTION)은 결과에서 빠지고, 미분석 원본 사진만 id 오름차순으로 남아야
        // "이번 실행이 처리할 이미지" 목록이 정확해진다.
        Long inspectionId = seedInspection();
        Media analyzed = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/analyzed.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        analyzed.markAnalyzed(LocalDateTime.of(2026, 8, 1, 10, 0));
        mediaRepository.save(analyzed);
        Media unanalyzed1 = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/unanalyzed1.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        Media unanalyzed2 = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/unanalyzed2.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/action-unanalyzed.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.DEFECT_ACTION).build());
        em.flush();

        List<Media> result = mediaRepository.findByInspectionIdAndFileTypeAndPurposeAndAnalyzedAtIsNullOrderByIdAsc(
                inspectionId, MediaFileType.IMAGE, MediaPurpose.INSPECTION_SOURCE);

        assertThat(result).extracting(Media::getId)
                .containsExactly(unanalyzed1.getId(), unanalyzed2.getId());
    }

    @Test
    void findByInspectionIdAndFileTypeAndPurposeAndAnalyzedAtIsNullOrderByIdAsc_전부분석완료면빈목록() {
        Long inspectionId = seedInspection();
        Media done = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/done.png").mimeSignatureVerified(true)
                .purpose(MediaPurpose.INSPECTION_SOURCE).build());
        done.markAnalyzed(LocalDateTime.of(2026, 8, 1, 10, 0));
        mediaRepository.save(done);
        em.flush();

        List<Media> result = mediaRepository.findByInspectionIdAndFileTypeAndPurposeAndAnalyzedAtIsNullOrderByIdAsc(
                inspectionId, MediaFileType.IMAGE, MediaPurpose.INSPECTION_SOURCE);

        assertThat(result).isEmpty();
    }

    @Test
    void markAnalyzed_호출후저장하면_analyzedAt이재조회시그대로복원된다() {
        Long inspectionId = seedInspection();
        Media media = mediaRepository.save(Media.builder()
                .inspectionId(inspectionId).fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/mark.png").mimeSignatureVerified(true)
                .build());
        assertThat(media.getAnalyzedAt()).isNull(); // 새 미디어는 항상 미분석 상태로 시작한다.

        LocalDateTime analyzedAt = LocalDateTime.of(2026, 8, 18, 15, 30, 0);
        media.markAnalyzed(analyzedAt);
        mediaRepository.save(media);
        em.flush();
        em.clear();

        Media reloaded = mediaRepository.findById(media.getId()).orElseThrow();
        assertThat(reloaded.getAnalyzedAt()).isEqualTo(analyzedAt);
    }
}
