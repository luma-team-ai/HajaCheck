package com.hajacheck.core.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse;
import com.hajacheck.core.facility.dto.FacilityComparisonResponse.ComparisonKpi;
import com.hajacheck.core.facility.dto.FacilityInspectionOverviewResponse;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FacilityInspectionOverviewServiceTest {

    @Mock
    private FacilityRepository facilityRepository;
    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private DefectRepository defectRepository;
    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private FacilityComparisonService facilityComparisonService;

    @InjectMocks
    private FacilityInspectionOverviewService service;

    private static final Long COMPANY_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final Long FACILITY_ID = 100L;
    private static final Long INSPECTOR_ID = 3L;

    private Facility facility() {
        Facility facility = Facility.builder()
                .companyId(COMPANY_ID)
                .name("테스트빌딩")
                .type("BUILDING")
                .build();
        ReflectionTestUtils.setField(facility, "id", FACILITY_ID);
        return facility;
    }

    private Inspection inspection(Long inspectionId, int roundNo, InspectionStatus status) {
        Inspection inspection = Inspection.builder()
                .facilityId(FACILITY_ID)
                .createdBy(USER_ID)
                .assignedInspectorId(INSPECTOR_ID)
                .roundNo(roundNo)
                .inspectionDate(LocalDate.of(2026, 1, roundNo))
                .status(status)
                .build();
        ReflectionTestUtils.setField(inspection, "id", inspectionId);
        return inspection;
    }

    private User inspector() {
        User user = User.builder().name("김검수").build();
        ReflectionTestUtils.setField(user, "id", INSPECTOR_ID);
        return user;
    }

    private Media media(Long id) {
        Media media = Media.builder()
                .fileType(MediaFileType.IMAGE)
                .originalUrl("stub")
                .mimeSignatureVerified(true)
                .build();
        ReflectionTestUtils.setField(media, "id", id);
        return media;
    }

    @Test
    void get_시설물이_없으면_FACILITY_NOT_FOUND() {
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID, COMPANY_ID, FACILITY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FACILITY_NOT_FOUND);
    }

    @Test
    void get_점검_회차가_없으면_전부_0과_빈_이력을_반환한다() {
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID)).thenReturn(List.of());

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        assertThat(response.overallGrade()).isNull();
        assertThat(response.totalRounds()).isZero();
        assertThat(response.cumulativeDefectCount()).isZero();
        assertThat(response.unresolvedDefectCount()).isZero();
        assertThat(response.history()).isEmpty();
    }

    @Test
    void get_회차가_1개뿐이면_changeNote는_null이고_최신회차_최악등급이_overallGrade다() {
        Inspection round1 = inspection(11L, 1, InspectionStatus.REVIEWED);
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID)).thenReturn(List.of(round1));
        when(mediaRepository.countGroupByInspectionIdsAndPurpose(any(), eq(MediaPurpose.INSPECTION_SOURCE))).thenReturn(List.of());
        when(mediaRepository.findTop2ByInspectionIdAndPurposeOrderByIdAsc(any(), eq(MediaPurpose.INSPECTION_SOURCE))).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionIdAndGrade(any()))
                .thenReturn(List.of(gradeCount(11L, DefectGrade.C, 2), gradeCount(11L, DefectGrade.E, 1)));
        when(defectRepository.countGroupByInspectionId(any())).thenReturn(List.of());
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(any(), any())).thenReturn(1L);
        when(userRepository.findAllById(any())).thenReturn(List.of(inspector()));

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        assertThat(response.totalRounds()).isEqualTo(1);
        assertThat(response.overallGrade()).isEqualTo(DefectGrade.E); // E가 C보다 심각(worst)
        assertThat(response.history()).hasSize(1);
        FacilityInspectionOverviewResponse.HistoryItem item = response.history().get(0);
        assertThat(item.status()).isEqualTo("검수완료");
        assertThat(item.inspectorName()).isEqualTo("김검수");
        assertThat(item.changeNote()).isNull(); // 비교할 이전 회차가 없음
    }

    @Test
    void get_최신회차가_비교가능상태면_changeNote를_채운다() {
        Inspection round1 = inspection(11L, 1, InspectionStatus.REVIEWED);
        Inspection round2 = inspection(12L, 2, InspectionStatus.REVIEWED);
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID))
                .thenReturn(List.of(round2, round1)); // 최신순
        when(mediaRepository.countGroupByInspectionIdsAndPurpose(any(), eq(MediaPurpose.INSPECTION_SOURCE))).thenReturn(List.of());
        when(mediaRepository.findTop2ByInspectionIdAndPurposeOrderByIdAsc(any(), eq(MediaPurpose.INSPECTION_SOURCE))).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionIdAndGrade(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionId(any())).thenReturn(List.of());
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(any(), any())).thenReturn(0L);
        when(userRepository.findAllById(any())).thenReturn(List.of(inspector()));
        when(facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2))
                .thenReturn(comparisonWith(3L, 1L));

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        FacilityInspectionOverviewResponse.HistoryItem latest = response.history().get(0);
        FacilityInspectionOverviewResponse.HistoryItem previous = response.history().get(1);
        assertThat(latest.changeNote()).isEqualTo("이전 회차 대비 신규 하자 +3건 · 진행성 1건");
        assertThat(previous.changeNote()).isNull(); // changeNote는 최신 회차에만 존재
    }

    @Test
    void get_최신회차가_아직_분석전이면_비교_없이_changeNote가_null이다() {
        Inspection round1 = inspection(11L, 1, InspectionStatus.REVIEWED);
        Inspection round2 = inspection(12L, 2, InspectionStatus.CREATED); // 아직 분석 전 — 비교 불가
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID))
                .thenReturn(List.of(round2, round1));
        when(mediaRepository.countGroupByInspectionIdsAndPurpose(any(), eq(MediaPurpose.INSPECTION_SOURCE))).thenReturn(List.of());
        when(mediaRepository.findTop2ByInspectionIdAndPurposeOrderByIdAsc(any(), eq(MediaPurpose.INSPECTION_SOURCE))).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionIdAndGrade(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionId(any())).thenReturn(List.of());
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(any(), any())).thenReturn(0L);
        when(userRepository.findAllById(any())).thenReturn(List.of(inspector()));

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        assertThat(response.history().get(0).changeNote()).isNull();
    }

    // #1549 — 최신 회차는 썸네일 URL을 채우고(최대 2장, media.id 기반 /api/media/{id}/thumbnail),
    // 이전 회차는 changeNote와 동일하게 빈 리스트여야 한다.
    @Test
    void get_최신회차_썸네일URL을_media_id_기반으로_채우고_이전회차는_빈리스트다() {
        Inspection round1 = inspection(11L, 1, InspectionStatus.REVIEWED);
        Inspection round2 = inspection(12L, 2, InspectionStatus.REVIEWED);
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID))
                .thenReturn(List.of(round2, round1));
        when(mediaRepository.countGroupByInspectionIdsAndPurpose(any(), eq(MediaPurpose.INSPECTION_SOURCE))).thenReturn(List.of());
        when(mediaRepository.findTop2ByInspectionIdAndPurposeOrderByIdAsc(12L, MediaPurpose.INSPECTION_SOURCE))
                .thenReturn(List.of(media(501L), media(502L)));
        when(defectRepository.countGroupByInspectionIdAndGrade(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionId(any())).thenReturn(List.of());
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(any(), any())).thenReturn(0L);
        when(userRepository.findAllById(any())).thenReturn(List.of(inspector()));
        when(facilityComparisonService.compare(USER_ID, COMPANY_ID, FACILITY_ID, 1, 2))
                .thenReturn(comparisonWith(0L, 0L));

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        FacilityInspectionOverviewResponse.HistoryItem latest = response.history().get(0);
        FacilityInspectionOverviewResponse.HistoryItem previous = response.history().get(1);
        assertThat(latest.thumbnailUrls()).containsExactly("/api/media/501/thumbnail", "/api/media/502/thumbnail");
        assertThat(previous.thumbnailUrls()).isEmpty();
    }

    // #1641 P2 — 사진 수 배지·미리보기 2장에 조치 후 사진이 섞이면 안 된다. 리포지토리 자체(실 PG 필터링)는
    // MediaRepositoryTest가 검증하고, 여기서는 서비스가 purpose=INSPECTION_SOURCE를 실제로 전달해
    // "원본 1장 + 조치 1장" 회차에서 배지가 1로, 미리보기에 조치 사진이 끼지 않는지 배선을 검증한다.
    @Test
    void get_원본1장_조치1장_회차에서_사진수배지는1이고_미리보기에조치사진은없다() {
        Inspection round1 = inspection(11L, 1, InspectionStatus.REVIEWED);
        when(facilityRepository.findByIdAndCompanyId(FACILITY_ID, COMPANY_ID)).thenReturn(Optional.of(facility()));
        when(inspectionRepository.findByFacilityIdOrderByRoundNoDesc(FACILITY_ID)).thenReturn(List.of(round1));
        // 리포지토리가 실제로 필터링한 결과를 흉내낸다 — 조치 사진(DEFECT_ACTION)은 카운트/목록 어디에도
        // 나타나지 않는다(실 필터 로직 자체는 MediaRepositoryTest가 실 PG로 검증).
        when(mediaRepository.countGroupByInspectionIdsAndPurpose(List.of(11L), MediaPurpose.INSPECTION_SOURCE))
                .thenReturn(List.of(inspectionMediaCount(11L, 1L)));
        when(mediaRepository.findTop2ByInspectionIdAndPurposeOrderByIdAsc(11L, MediaPurpose.INSPECTION_SOURCE))
                .thenReturn(List.of(media(601L)));
        when(defectRepository.countGroupByInspectionIdAndGrade(any())).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionId(any())).thenReturn(List.of());
        when(defectRepository.countByInspectionIdInAndDeletedFalseAndStatusNot(any(), any())).thenReturn(0L);
        when(userRepository.findAllById(any())).thenReturn(List.of(inspector()));

        FacilityInspectionOverviewResponse response = service.get(USER_ID, COMPANY_ID, FACILITY_ID);

        FacilityInspectionOverviewResponse.HistoryItem item = response.history().get(0);
        assertThat(item.imageCount()).isEqualTo(1L); // 조치 사진은 배지에 포함되지 않는다.
        assertThat(item.thumbnailUrls()).containsExactly("/api/media/601/thumbnail"); // 원본 사진만.
        // 서비스가 "전체" 변형(countGroupByInspectionIds/findTop2ByInspectionIdOrderByIdAsc)이 아니라
        // purpose 필터 변형을 호출했는지도 함께 고정한다(잘못된 오버로드로 되돌아가는 회귀 방지).
        org.mockito.Mockito.verify(mediaRepository, org.mockito.Mockito.never())
                .countGroupByInspectionIds(any());
        org.mockito.Mockito.verify(mediaRepository, org.mockito.Mockito.never())
                .findTop2ByInspectionIdOrderByIdAsc(any());
    }

    private MediaRepository.InspectionMediaCountProjection inspectionMediaCount(Long inspectionId, long cnt) {
        return new MediaRepository.InspectionMediaCountProjection() {
            @Override
            public Long getInspectionId() {
                return inspectionId;
            }

            @Override
            public long getCnt() {
                return cnt;
            }
        };
    }

    private InspectionGradeCountProjection gradeCount(Long inspectionId, DefectGrade grade, long cnt) {
        return new InspectionGradeCountProjection() {
            @Override
            public Long getInspectionId() {
                return inspectionId;
            }

            @Override
            public DefectGrade getGrade() {
                return grade;
            }

            @Override
            public long getCnt() {
                return cnt;
            }
        };
    }

    private FacilityComparisonResponse comparisonWith(long newDefects, long worsening) {
        return new FacilityComparisonResponse(
                FACILITY_ID,
                "테스트빌딩",
                new FacilityComparisonResponse.CycleOption(1, LocalDate.of(2026, 1, 1)),
                new FacilityComparisonResponse.CycleOption(2, LocalDate.of(2026, 1, 2)),
                List.of(
                        new ComparisonKpi("newDefects", "신규 하자", newDefects, newDefects),
                        new ComparisonKpi("worsening", "진행성 (악화)", worsening, worsening)),
                List.of(),
                List.of(),
                null,
                null);
    }
}
