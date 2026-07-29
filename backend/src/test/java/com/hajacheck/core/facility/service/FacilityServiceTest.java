package com.hajacheck.core.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityStatusResponse;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.FacilityInitialGrade;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.FacilityDefectCountProjection;
import com.hajacheck.core.defect.repository.FacilityGradeCountProjection;
import com.hajacheck.core.defect.repository.FacilityLatestDefectProjection;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.FacilityRepresentativeMediaProjection;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.service.QuotaService;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class FacilityServiceTest {

    @Mock
    private FacilityRepository facilityRepository;
    @Mock
    private CompanyScopeGuard companyScopeGuard;

    @Mock
    private AuthService authService;

    @Mock
    private InspectionRepository inspectionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private QuotaService quotaService;

    @Mock
    private DefectRepository defectRepository;

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private FileStorageService fileStorage;

    @InjectMocks
    private FacilityService facilityService;

    private static final Long OWNER_ID = 1L;
    private static final Long USER_ID = 101L;

    private Facility existingFacility() {
        return Facility.builder()
                .companyId(OWNER_ID)
                .name("기존시설")
                .type("BUILDING")
                .address("서울시 강남구")
                .build();
    }

    // 리플렉션으로 id 를 채워 Map 조립(facilityId 기준)이 검증 가능하게 한다 — Facility.id 는
    // @GeneratedValue라 빌더로 직접 설정할 수 없다(FacilityResponse.from 등 기존 테스트는 id 검증을
    // 하지 않아 문제되지 않았지만, listStatus 는 facility.getId() 로 Map 조회를 하므로 필요).
    private Facility facilityWithId(Long id, String name, FacilityInitialGrade grade,
                                     LocalDate nextInspectionDueAt, Long assigneeUserId) {
        Facility facility = Facility.builder()
                .companyId(OWNER_ID)
                .name(name)
                .type("BUILDING")
                .address("서울시 강남구")
                .initialGrade(grade)
                .nextInspectionDueAt(nextInspectionDueAt)
                .assigneeUserId(assigneeUserId)
                .build();
        setId(facility, id);
        return facility;
    }

    private void setId(Facility facility, Long id) {
        try {
            Field idField = Facility.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(facility, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private FacilityCreateRequest createRequest() {
        return new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", "서울시 강남구", null, null, 2010, "지상5층", 12, null,
                null, null, null);
    }

    @Test
    void create_등록_소유자와입력값으로저장() {
        when(facilityRepository.save(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));

        FacilityResponse response = facilityService.create(USER_ID, OWNER_ID, createRequest());

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(OWNER_ID);
        assertThat(captor.getValue().getName()).isEqualTo("테스트빌딩");
        assertThat(response.name()).isEqualTo("테스트빌딩");
    }

    @Test
    void list_목록조회_소유자스코프로위임() {
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(existingFacility()));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("기존시설");
        verify(facilityRepository).findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class));
    }

    // HAJA-434 갭1 P1 픽스 — PR머신이 지적한 회귀: list()가 latestDefectId를 안 채우면 목록 화면에서
    // "시설물 클릭 시 하자 오버레이 직행"이 항상 폴백된다. get()만이 아니라 list()도 채우는지 고정한다.
    @Test
    void list_하자있는시설_대표하자ID채워서반환() {
        Facility facility = facilityWithId(10L, "강남 오피스타워", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(defectRepository.findLatestByFacilityIds(eq(List.of(10L)), eq(OWNER_ID)))
                .thenReturn(List.of(new FacilityLatestDefectProjection() {
                    public Long getFacilityId() {
                        return 10L;
                    }

                    public Long getDefectId() {
                        return 777L;
                    }
                }));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).latestDefectId()).isEqualTo(777L);
    }

    @Test
    void list_하자없는시설_대표하자ID는null() {
        Facility facility = facilityWithId(10L, "강남 오피스타워", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(defectRepository.findLatestByFacilityIds(eq(List.of(10L)), eq(OWNER_ID)))
                .thenReturn(List.of());

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).latestDefectId()).isNull();
    }

    // code-reviewer P2 — 시설물 1건짜리 테스트만으로는 facilityId별 그룹핑(첫 값=최신 유지)이
    // 실제로 동작하는지 못 잡는다(예: mergeFunction 누락해도 통과할 수 있음). 시설물 2건 +
    // 각각 하자 2건(쿼리 정렬 순서인 createdAt desc를 그대로 재현) 조합으로 교차오염 여부를 고정한다.
    @Test
    void list_시설물여러건_각시설물별로대표하자ID가섞이지않는다() {
        Facility facilityA = facilityWithId(10L, "강남 오피스타워", null, null, null);
        Facility facilityB = facilityWithId(20L, "한강대교 북단", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facilityA, facilityB));
        // facilityId asc, createdAt desc 정렬을 그대로 재현 — 시설물별 첫 행이 최신 하자다.
        when(defectRepository.findLatestByFacilityIds(eq(List.of(10L, 20L)), eq(OWNER_ID)))
                .thenReturn(List.of(
                        projection(10L, 501L),
                        projection(10L, 502L),
                        projection(20L, 601L)));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(10L);
        assertThat(result.get(0).latestDefectId()).isEqualTo(501L);
        assertThat(result.get(1).id()).isEqualTo(20L);
        assertThat(result.get(1).latestDefectId()).isEqualTo(601L);
    }

    private static FacilityLatestDefectProjection projection(Long facilityId, Long defectId) {
        return new FacilityLatestDefectProjection() {
            public Long getFacilityId() {
                return facilityId;
            }

            public Long getDefectId() {
                return defectId;
            }
        };
    }

    // ── 시설물 카드 하자건수 배지(HAJA-515/#1075) ──

    private static FacilityDefectCountProjection countProjection(Long facilityId, long cnt) {
        return new FacilityDefectCountProjection() {
            public Long getFacilityId() {
                return facilityId;
            }

            public long getCnt() {
                return cnt;
            }
        };
    }

    @Test
    void list_하자있는시설_하자건수채워서반환() {
        Facility facility = facilityWithId(10L, "강남 오피스타워", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(defectRepository.countGroupByFacilityIds(eq(List.of(10L)), eq(OWNER_ID)))
                .thenReturn(List.of(countProjection(10L, 3L)));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).defectCount()).isEqualTo(3L);
    }

    // 하자가 0건인 시설물은 group by 결과에 로우 자체가 없다(count(d)를 만들 대상 행이 없음) — 서비스가
    // getOrDefault로 0을 명시 채우는지, 즉 defectCount가 null이 아니라 정말 0인지 고정한다.
    @Test
    void list_하자없는시설_하자건수는0() {
        Facility facility = facilityWithId(10L, "강남 오피스타워", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(defectRepository.countGroupByFacilityIds(eq(List.of(10L)), eq(OWNER_ID)))
                .thenReturn(List.of());

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).defectCount()).isEqualTo(0L);
    }

    // code-reviewer P2 선례(latestDefectId/썸네일 교차오염 테스트)와 동일 이유 — 시설물 1건짜리 테스트만으로는
    // facilityId별 Map 조립이 실제로 동작하는지(다른 시설물의 건수가 섞이지 않는지) 못 잡는다. 이 테스트가
    // countGroupByFacilityIds를 시설물 수만큼 반복 호출하지 않고 배치(합쳐진 facilityIds) 1회로만
    // 호출하는지도 함께 고정한다 — 개별 호출이었다면 이 스텁(List.of(10L, 20L) 키)이 매칭되지 않아 실패한다.
    @Test
    void list_시설물여러건_각시설물별로하자건수가섞이지않는다() {
        Facility facilityA = facilityWithId(10L, "강남 오피스타워", null, null, null);
        Facility facilityB = facilityWithId(20L, "한강대교 북단", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facilityA, facilityB));
        when(defectRepository.countGroupByFacilityIds(eq(List.of(10L, 20L)), eq(OWNER_ID)))
                .thenReturn(List.of(countProjection(10L, 5L), countProjection(20L, 2L)));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).id()).isEqualTo(10L);
        assertThat(result.get(0).defectCount()).isEqualTo(5L);
        assertThat(result.get(1).id()).isEqualTo(20L);
        assertThat(result.get(1).defectCount()).isEqualTo(2L);
        verify(defectRepository, times(1)).countGroupByFacilityIds(eq(List.of(10L, 20L)), eq(OWNER_ID));
    }

    @Test
    void get_하자있는시설_하자건수채워서반환() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        when(defectRepository.countGroupByFacilityIds(eq(List.of(10L)), eq(OWNER_ID)))
                .thenReturn(List.of(countProjection(10L, 4L)));

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.defectCount()).isEqualTo(4L);
    }

    @Test
    void get_하자없는시설_하자건수는0() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        when(defectRepository.countGroupByFacilityIds(eq(List.of(10L)), eq(OWNER_ID)))
                .thenReturn(List.of());

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.defectCount()).isEqualTo(0L);
    }

    // ── 시설물 목록/상세 대표 사진 썸네일(HAJA-367/#670) ──

    private static FacilityRepresentativeMediaProjection mediaProjection(Long facilityId, Long mediaId) {
        return new FacilityRepresentativeMediaProjection() {
            public Long getFacilityId() {
                return facilityId;
            }

            public Long getMediaId() {
                return mediaId;
            }
        };
    }

    @Test
    void list_대표사진있는시설_썸네일URL채워서반환() {
        Facility facility = facilityWithId(10L, "강남 오피스타워", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(mediaRepository.findFirstIdsByFacilityIds(eq(List.of(10L)), eq(OWNER_ID)))
                .thenReturn(List.of(mediaProjection(10L, 900L)));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).thumbnailUrl()).isEqualTo("/api/media/900/thumbnail");
    }

    @Test
    void list_대표사진없는시설_썸네일URL은null() {
        Facility facility = facilityWithId(10L, "강남 오피스타워", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(mediaRepository.findFirstIdsByFacilityIds(eq(List.of(10L)), eq(OWNER_ID)))
                .thenReturn(List.of());

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).thumbnailUrl()).isNull();
    }

    // code-reviewer P2 선례(latestDefectId 교차오염 테스트)와 동일 이유 — 시설물 1건짜리 테스트만으로는
    // facilityId별 그룹핑(첫 값=최초 등록 사진 유지)이 실제로 동작하는지 못 잡는다.
    @Test
    void list_시설물여러건_각시설물별로썸네일이섞이지않는다() {
        Facility facilityA = facilityWithId(10L, "강남 오피스타워", null, null, null);
        Facility facilityB = facilityWithId(20L, "한강대교 북단", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facilityA, facilityB));
        // facilityId asc, id asc 정렬을 그대로 재현 — 시설물별 첫 행이 최초 등록 사진이다.
        when(mediaRepository.findFirstIdsByFacilityIds(eq(List.of(10L, 20L)), eq(OWNER_ID)))
                .thenReturn(List.of(
                        mediaProjection(10L, 901L),
                        mediaProjection(10L, 902L),
                        mediaProjection(20L, 903L)));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).id()).isEqualTo(10L);
        assertThat(result.get(0).thumbnailUrl()).isEqualTo("/api/media/901/thumbnail");
        assertThat(result.get(1).id()).isEqualTo(20L);
        assertThat(result.get(1).thumbnailUrl()).isEqualTo("/api/media/903/thumbnail");
    }

    @Test
    void get_대표사진있는시설_썸네일URL채워서반환() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        Media photo = Media.builder()
                .facilityId(10L)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/1-original.png")
                .thumbnailUrl("facility-media-thumb/1-thumb.jpg")
                .mimeSignatureVerified(true)
                .build();
        setMediaId(photo, 900L);
        when(mediaRepository.findByFacilityIdOrderByIdAsc(10L)).thenReturn(List.of(photo));

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.thumbnailUrl()).isEqualTo("/api/media/900/thumbnail");
    }

    @Test
    void get_대표사진없는시설_썸네일URL은null() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        when(mediaRepository.findByFacilityIdOrderByIdAsc(10L)).thenReturn(List.of());

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.thumbnailUrl()).isNull();
    }

    // ── 시설물 카드 "최근 점검 MM.dd"(HAJA-514/#1074) ──

    @Test
    void list_점검이력있는시설_최근점검일채워서반환() {
        Facility facility = facilityWithId(10L, "강남 오피스타워", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        Inspection lastInspection = Inspection.builder()
                .facilityId(10L).createdBy(USER_ID).assignedInspectorId(USER_ID).roundNo(2)
                .inspectionDate(LocalDate.of(2026, 6, 21)).status(InspectionStatus.CREATED).build();
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L))).thenReturn(List.of(lastInspection));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).lastInspectedAt()).isEqualTo(LocalDate.of(2026, 6, 21));
    }

    @Test
    void list_점검하자등급집계_지도용최고등급과경고주의건수를채운다() {
        Facility facility = facilityWithId(10L, "한강대교 북단", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        Inspection inspection = Inspection.builder()
                .facilityId(10L).createdBy(USER_ID).assignedInspectorId(USER_ID).roundNo(1)
                .inspectionDate(LocalDate.of(2026, 6, 21)).status(InspectionStatus.REVIEWED).build();
        setInspectionId(inspection, 100L);
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L))).thenReturn(List.of(inspection));
        when(defectRepository.countGroupByFacilityIdAndGrade(List.of(100L))).thenReturn(List.of(
                facilityGradeCount(10L, DefectGrade.C, 2L),
                facilityGradeCount(10L, DefectGrade.E, 1L),
                facilityGradeCount(10L, DefectGrade.D, 3L)));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).highestGrade()).isEqualTo("E");
        assertThat(result.get(0).warningCount()).isEqualTo(4L);
        assertThat(result.get(0).cautionCount()).isEqualTo(2L);
    }

    @Test
    void list_여러점검중최신점검만하자등급집계에반영된다() {
        Facility facility = facilityWithId(10L, "한강대교 북단", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        // 과거 점검(100L) — E등급 3건 + D등급 1건 (심각)
        Inspection pastInspection = Inspection.builder()
                .facilityId(10L).createdBy(USER_ID).assignedInspectorId(USER_ID).roundNo(1)
                .inspectionDate(LocalDate.of(2026, 5, 1)).status(InspectionStatus.REVIEWED).build();
        setInspectionId(pastInspection, 100L);
        // 최신 점검(200L) — C등급 2건 (경미) — 이 점검의 하자만 집계되어야 함
        Inspection latestInspection = Inspection.builder()
                .facilityId(10L).createdBy(USER_ID).assignedInspectorId(USER_ID).roundNo(2)
                .inspectionDate(LocalDate.of(2026, 6, 21)).status(InspectionStatus.REVIEWED).build();
        setInspectionId(latestInspection, 200L);
        // findLatestByFacilityIds는 최신 점검 1건만 반환
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L)))
                .thenReturn(List.of(latestInspection));
        // countGroupByFacilityIdAndGrade는 최신 점검(200L)의 하자만 조회
        when(defectRepository.countGroupByFacilityIdAndGrade(List.of(200L))).thenReturn(List.of(
                facilityGradeCount(10L, DefectGrade.C, 2L)));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        // 과거 점검의 E등급 하자는 집계에서 제외되어야 함
        assertThat(result.get(0).highestGrade()).isEqualTo("C");
        assertThat(result.get(0).warningCount()).isEqualTo(0L);
        assertThat(result.get(0).cautionCount()).isEqualTo(2L);
    }

    @Test
    void list_점검이력없는시설_최근점검일은null() {
        Facility facility = facilityWithId(10L, "강남 오피스타워", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L))).thenReturn(List.of());

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result.get(0).lastInspectedAt()).isNull();
    }

    @Test
    void get_점검이력있는시설_최근점검일채워서반환() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        Inspection lastInspection = Inspection.builder()
                .facilityId(10L).createdBy(USER_ID).assignedInspectorId(USER_ID).roundNo(1)
                .inspectionDate(LocalDate.of(2026, 6, 28)).status(InspectionStatus.CREATED).build();
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L))).thenReturn(List.of(lastInspection));

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.lastInspectedAt()).isEqualTo(LocalDate.of(2026, 6, 28));
    }

    @Test
    void get_여러점검중최신점검만하자등급집계에반영된다() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        // 과거 점검(100L) — E등급 1건 (심각)
        Inspection pastInspection = Inspection.builder()
                .facilityId(10L).createdBy(USER_ID).assignedInspectorId(USER_ID).roundNo(1)
                .inspectionDate(LocalDate.of(2026, 5, 1)).status(InspectionStatus.REVIEWED).build();
        setInspectionId(pastInspection, 100L);
        // 최신 점검(200L) — D등급 2건 (경고) — 이 점검의 하자만 집계되어야 함
        Inspection latestInspection = Inspection.builder()
                .facilityId(10L).createdBy(USER_ID).assignedInspectorId(USER_ID).roundNo(2)
                .inspectionDate(LocalDate.of(2026, 6, 21)).status(InspectionStatus.REVIEWED).build();
        setInspectionId(latestInspection, 200L);
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L)))
                .thenReturn(List.of(latestInspection));
        when(defectRepository.countGroupByFacilityIdAndGrade(List.of(200L))).thenReturn(List.of(
                facilityGradeCount(10L, DefectGrade.D, 2L)));

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.highestGrade()).isEqualTo("D");
        assertThat(response.warningCount()).isEqualTo(2L);
        assertThat(response.cautionCount()).isEqualTo(0L);
    }

    @Test
    void get_점검이력없는시설_최근점검일은null() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L))).thenReturn(List.of());

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.lastInspectedAt()).isNull();
    }

    private void setMediaId(Media media, Long id) {
        try {
            Field idField = Media.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(media, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setInspectionId(Inspection inspection, Long id) {
        try {
            Field idField = Inspection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(inspection, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FacilityGradeCountProjection facilityGradeCount(Long facilityId, DefectGrade grade, Long count) {
        return new FacilityGradeCountProjection() {
            public Long getFacilityId() {
                return facilityId;
            }

            public DefectGrade getGrade() {
                return grade;
            }

            public Long getCnt() {
                return count;
            }
        };
    }

    @Test
    void list_목록조회_상한초과시상한개수만반환() {
        List<Facility> capped = List.of(existingFacility(), existingFacility());
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(capped);

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result).hasSize(2);
        ArgumentCaptor<PageRequest> pageableCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(facilityRepository).findByCompanyIdOrderByIdAsc(eq(OWNER_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(500);
        // 상한 미도달이면 무고지 truncation 감지용 countByCompanyId 를 호출할 필요가 없다(#502 P2).
        verify(facilityRepository, never()).countByCompanyId(any());
    }

    @Test
    void list_상한정확히도달시_실제보유건수를조회해WARN로그근거를남긴다() {
        List<Facility> maxed = java.util.Collections.nCopies(500, existingFacility());
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(maxed);
        when(facilityRepository.countByCompanyId(OWNER_ID)).thenReturn(650L);

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result).hasSize(500);
        // 응답 계약(List)은 그대로 500건 — 무고지 truncation 감지는 로그로만 이뤄지므로, 최소한 실제
        // 보유 건수(countByCompanyId)를 조회했는지로 "감지 로직이 탔다"를 검증한다(#502 P2).
        verify(facilityRepository).countByCompanyId(OWNER_ID);
    }

    @Test
    void get_존재하는본인시설_반환() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.name()).isEqualTo("기존시설");
    }

    // HAJA-434 갭1 — 시설물 상세→하자 오버레이 직행을 위한 대표(최신) 하자 id.
    @Test
    void get_하자있는시설_대표하자ID채워서반환() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        when(defectRepository.findLatestIdsByFacility(eq(10L), eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(555L));

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.latestDefectId()).isEqualTo(555L);
        verify(defectRepository).findLatestIdsByFacility(eq(10L), eq(OWNER_ID), any(PageRequest.class));
    }

    @Test
    void get_하자없는시설_대표하자ID는null() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        when(defectRepository.findLatestIdsByFacility(eq(10L), eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of());

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.latestDefectId()).isNull();
    }

    @Test
    void get_없는시설_FACILITY_NOT_FOUND예외() {
        when(facilityRepository.findByIdAndCompanyId(999L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityService.get(USER_ID, OWNER_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }

    @Test
    void get_타인소유시설_FACILITY_NOT_FOUND예외() {
        // findByIdAndCompanyId 는 소유자 스코프라 타인 소유는 조회 자체가 빈 값으로 온다(cross-owner IDOR 방지).
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityService.get(USER_ID, OWNER_ID, 10L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void update_본인시설_필드갱신() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        FacilityUpdateRequest request = new FacilityUpdateRequest(
                "수정된빌딩", "APARTMENT", "서울시 서초구", null, null, 2015, "지상10층", 6, null,
                null, null, null);

        FacilityResponse response = facilityService.update(USER_ID, OWNER_ID, 10L, request);

        assertThat(response.name()).isEqualTo("수정된빌딩");
        assertThat(response.type()).isEqualTo("APARTMENT");
        assertThat(response.address()).isEqualTo("서울시 서초구");
        assertThat(response.inspectionCycleMonths()).isEqualTo(6);
    }

    @Test
    void update_없는시설_FACILITY_NOT_FOUND예외() {
        when(facilityRepository.findByIdAndCompanyId(999L, OWNER_ID)).thenReturn(Optional.empty());
        FacilityUpdateRequest request = new FacilityUpdateRequest(
                "수정된빌딩", "APARTMENT", null, null, null, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> facilityService.update(USER_ID, OWNER_ID, 999L, request))
                .isInstanceOf(BusinessException.class);
    }

    // ── 시설물 등록 필드 확장(#628 / HAJA-347) ──
    // 대표 사진(photoUrls)은 Polalise DDL 검토 후 별도 후속으로 반영 예정(#632) — 이번 범위 테스트 제외.

    @Test
    void create_초기등급담당자메모_함께저장() {
        when(facilityRepository.save(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));
        FacilityCreateRequest request = new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", "서울시 강남구", null, null, 2010, "지상5층", 12, null,
                FacilityInitialGrade.B, 5L, "1층 로비 CCTV 사각지대 있음");

        FacilityResponse response = facilityService.create(USER_ID, OWNER_ID, request);

        verify(authService).validateAssignableInspector(USER_ID, 5L);
        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertThat(captor.getValue().getInitialGrade()).isEqualTo(FacilityInitialGrade.B);
        assertThat(captor.getValue().getAssigneeUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getMemo()).isEqualTo("1층 로비 CCTV 사각지대 있음");
        assertThat(response.initialGrade()).isEqualTo(FacilityInitialGrade.B);
        assertThat(response.assigneeUserId()).isEqualTo(5L);
        assertThat(response.memo()).isEqualTo("1층 로비 CCTV 사각지대 있음");
    }

    @Test
    void create_담당자없음_담당자검증호출안함() {
        when(facilityRepository.save(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));

        facilityService.create(USER_ID, OWNER_ID, createRequest());

        verify(authService, never()).validateAssignableInspector(any(), any());
    }

    @Test
    void create_배정불가담당자_AUTH_INVALID_INSPECTOR예외_저장호출없음() {
        doThrow(new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR))
                .when(authService).validateAssignableInspector(USER_ID, 999L);
        FacilityCreateRequest request = new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", null, null, null, null, null, null, null,
                null, 999L, null);

        assertThatThrownBy(() -> facilityService.create(USER_ID, OWNER_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
        verify(facilityRepository, never()).save(any());
    }

    @Test
    void delete_본인시설_저장소에서삭제() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        when(mediaRepository.findByFacilityIdOrderByIdAsc(10L)).thenReturn(List.of());

        facilityService.delete(USER_ID, OWNER_ID, 10L);

        verify(facilityRepository, times(1)).delete(facility);
    }

    // #1024 — fk_media_facility(NO ACTION) 위반 방지: 대표 사진이 있으면 스토리지 파일 삭제 +
    // media 로우 삭제가 facilityRepository.delete()보다 먼저 일어나야 한다.
    @Test
    void delete_대표사진있는시설_미디어와스토리지정리후시설물삭제() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        Media photo1 = Media.builder()
                .facilityId(10L)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/1-original.png")
                .thumbnailUrl("facility-media-thumb/1-thumb.jpg")
                .detailUrl("facility-media-detail/1-detail.jpg")
                .mimeSignatureVerified(true)
                .build();
        Media photo2 = Media.builder()
                .facilityId(10L)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("facility-media/2-original.png")
                .thumbnailUrl(null)
                .detailUrl(null)
                .mimeSignatureVerified(true)
                .build();
        List<Media> facilityMedia = List.of(photo1, photo2);
        when(mediaRepository.findByFacilityIdOrderByIdAsc(10L)).thenReturn(facilityMedia);

        facilityService.delete(USER_ID, OWNER_ID, 10L);

        verify(fileStorage).delete("facility-media/1-original.png");
        verify(fileStorage).delete("facility-media-thumb/1-thumb.jpg");
        verify(fileStorage).delete("facility-media-detail/1-detail.jpg");
        verify(fileStorage).delete("facility-media/2-original.png");
        verify(fileStorage, times(2)).delete((String) null);

        InOrder order = inOrder(mediaRepository, facilityRepository);
        order.verify(mediaRepository).deleteAll(facilityMedia);
        order.verify(facilityRepository).delete(facility);
    }

    // 회귀 방지 — 대표 사진이 없는 시설물은 media 관련 정리 호출이 전혀 일어나지 않아야 한다.
    @Test
    void delete_대표사진없는시설_미디어관련호출없이정상삭제() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        when(mediaRepository.findByFacilityIdOrderByIdAsc(10L)).thenReturn(List.of());

        facilityService.delete(USER_ID, OWNER_ID, 10L);

        verify(facilityRepository, times(1)).delete(facility);
        verify(mediaRepository, never()).deleteAll(any());
        verify(fileStorage, never()).delete(any());
    }

    @Test
    void delete_없는시설_FACILITY_NOT_FOUND예외_삭제호출없음() {
        when(facilityRepository.findByIdAndCompanyId(999L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityService.delete(USER_ID, OWNER_ID, 999L))
                .isInstanceOf(BusinessException.class);
        verify(facilityRepository, never()).delete(any(Facility.class));
    }

    @Test
    void lockForUpdate_행잠금조회위임() {
        facilityService.lockForUpdate(10L);

        verify(facilityRepository).findByIdForUpdate(10L);
    }

    @Test
    void setSchedule_본인시설_다음점검일산출저장() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        FacilityScheduleRequest request = new FacilityScheduleRequest(6);

        FacilityResponse response = facilityService.setSchedule(USER_ID, OWNER_ID, 10L, request);

        assertThat(response.inspectionCycleMonths()).isEqualTo(6);
        assertThat(response.nextInspectionDueAt()).isEqualTo(LocalDate.now().plusMonths(6));
    }

    @Test
    void setSchedule_없는시설_FACILITY_NOT_FOUND예외() {
        when(facilityRepository.findByIdAndCompanyId(999L, OWNER_ID)).thenReturn(Optional.empty());
        FacilityScheduleRequest request = new FacilityScheduleRequest(12);

        assertThatThrownBy(() -> facilityService.setSchedule(USER_ID, OWNER_ID, 999L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }

    @Test
    void setSchedule_타인소유시설_FACILITY_NOT_FOUND예외() {
        // findByIdAndCompanyId 는 소유자 스코프라 타인 소유는 조회 자체가 빈 값으로 온다(cross-owner IDOR 방지).
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.empty());
        FacilityScheduleRequest request = new FacilityScheduleRequest(12);

        assertThatThrownBy(() -> facilityService.setSchedule(USER_ID, OWNER_ID, 10L, request))
                .isInstanceOf(BusinessException.class);
    }
    @Test
    void list_회사없는사용자_FORBIDDEN예외() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(USER_ID, null);
        assertThatThrownBy(() -> facilityService.list(USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ── 시설물 현황 전용 목록(#540 ⑥, HAJA-378) ──

    @Test
    void listStatus_회사시설없으면_빈목록_배치조회미호출() {
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of());

        List<FacilityStatusResponse> result = facilityService.listStatus(USER_ID, OWNER_ID);

        assertThat(result).isEmpty();
        verify(inspectionRepository, never()).findLatestByFacilityIds(any());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void listStatus_담당자와점검이력있는시설_모든필드조립() {
        // dDay는 서비스가 KST 기준(FacilityService.KST)으로 산출하므로, CI(UTC 러너)에서
        // 시스템 기본 zone(LocalDate.now())으로 만들면 자정 전후 9시간 구간에서 하루 어긋난다 —
        // 같은 KST로 맞춰야 CI/로컬 무관하게 결정론적으로 통과한다.
        // #1136 — inspectionCycleMonths/inspectionType 노출 검증을 위해 helper(facilityWithId,
        // 다른 11개 테스트가 공유) 시그니처를 건드리지 않고 이 테스트만 빌더로 직접 구성한다.
        Facility facility = Facility.builder()
                .companyId(OWNER_ID)
                .name("테스트빌딩")
                .type("BUILDING")
                .initialGrade(FacilityInitialGrade.C)
                .nextInspectionDueAt(LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(5))
                .assigneeUserId(5L)
                .inspectionCycleMonths(6)
                .build();
        setId(facility, 10L);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));

        // type을 기본값(REGULAR)이 아닌 DETAILED로 둬 "항상 REGULAR라서 우연히 통과"를 배제한다.
        Inspection lastInspection = Inspection.builder()
                .facilityId(10L)
                .createdBy(USER_ID)
                .assignedInspectorId(5L)
                .roundNo(2)
                .inspectionDate(LocalDate.now().minusDays(3))
                .type(InspectionType.DETAILED)
                .status(InspectionStatus.CREATED)
                .build();
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L))).thenReturn(List.of(lastInspection));

        User assignee = User.builder()
                .email("assignee@haja.com").name("담당자김").role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed").status(UserStatus.ACTIVE).build();
        setUserId(assignee, 5L);
        when(userRepository.findAllById(List.of(5L))).thenReturn(List.of(assignee));

        List<FacilityStatusResponse> result = facilityService.listStatus(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        FacilityStatusResponse status = result.get(0);
        assertThat(status.facilityId()).isEqualTo(10L);
        assertThat(status.facilityName()).isEqualTo("테스트빌딩");
        assertThat(status.initialGrade()).isEqualTo(FacilityInitialGrade.C);
        assertThat(status.dDay()).isEqualTo(5L);
        assertThat(status.assigneeUserId()).isEqualTo(5L);
        assertThat(status.assigneeName()).isEqualTo("담당자김");
        assertThat(status.lastInspectedAt()).isEqualTo(LocalDate.now().minusDays(3));
        assertThat(status.inspectionCycleMonths()).isEqualTo(6);
        assertThat(status.inspectionType()).isEqualTo(InspectionType.DETAILED);
    }

    @Test
    void listStatus_담당자없고점검이력없는시설_null필드로반환_에러없음() {
        Facility facility = facilityWithId(11L, "미배정시설", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(inspectionRepository.findLatestByFacilityIds(List.of(11L))).thenReturn(List.of());

        List<FacilityStatusResponse> result = facilityService.listStatus(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        FacilityStatusResponse status = result.get(0);
        assertThat(status.facilityId()).isEqualTo(11L);
        assertThat(status.initialGrade()).isNull();
        assertThat(status.nextInspectionDueAt()).isNull();
        assertThat(status.dDay()).isNull();
        assertThat(status.assigneeUserId()).isNull();
        assertThat(status.assigneeName()).isNull();
        assertThat(status.lastInspectedAt()).isNull();
        assertThat(status.inspectionCycleMonths()).isNull();
        assertThat(status.inspectionType()).isNull();
        // 담당자 배정된 시설이 하나도 없으면 배치 사용자 조회 자체를 생략한다(불필요 쿼리 방지).
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void listStatus_회사스코프검증_먼저호출() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(USER_ID, OWNER_ID);

        assertThatThrownBy(() -> facilityService.listStatus(USER_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(facilityRepository, never()).findByCompanyIdOrderByIdAsc(any(), any());
    }

    private void setUserId(User user, Long id) {
        try {
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
