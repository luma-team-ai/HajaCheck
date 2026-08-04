package com.hajacheck.core.facility.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.FacilityDefectCountProjection;
import com.hajacheck.core.defect.repository.FacilityGradeCountProjection;
import com.hajacheck.core.defect.repository.FacilityLatestDefectProjection;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityStatusResponse;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.repository.FacilityRepresentativeMediaProjection;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.service.QuotaService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시설물 CRUD — 모든 조회/수정/삭제는 로그인 사용자의 회사 스코프로 제한한다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FacilityService {

    // 시설물 목록 조회 상한(#484) — 계약(응답 배열) 은 그대로 유지한 채 무제한 반환을 막는 방어적 상한.
    // 대시보드 RECENT_LIMIT(10)·알림 LIST_LIMIT(30)·다가오는점검 UPCOMING_INSPECTIONS_MAX_LIMIT(50) 은
    // "요약/미리보기" 목적이라 작지만, 시설물 목록은 관리 대상 자산 전체를 보여주는 화면이라 그보다
    // 훨씬 크게 잡는다. 진짜 페이지네이션(Page 응답) 전환 전까지의 임시 방어값.
    private static final int FACILITY_LIST_MAX = 500;

    // 시설물 현황 목록(#540 ⑥, HAJA-378) — dDay 산출 기준 시각대(DashboardService 와 동일 관례).
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final FacilityRepository facilityRepository;
    private final CompanyScopeGuard companyScopeGuard;
    private final AuthService authService;
    private final InspectionRepository inspectionRepository;
    private final UserRepository userRepository;
    private final QuotaService quotaService;
    private final DefectRepository defectRepository;
    private final MediaRepository mediaRepository;
    private final FileStorageService fileStorage;

    @Transactional
    public FacilityResponse create(Long userId, Long companyId, FacilityCreateRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        // 플랜 한도(plans.max_facilities) 강제(#843) — 회사 스코프를 DB 로 재검증한 직후, 저장 전에 슬롯을
        // 예약한다. 같은 트랜잭션이라 아래 save 가 실패하면 예약도 함께 롤백된다.
        quotaService.reserveFacilitySlot(userId, companyId);
        validateAssigneeIfPresent(userId, request.assigneeUserId());
        Facility facility = Facility.builder()
                .companyId(companyId)
                .name(request.name())
                .type(request.type())
                .address(request.address())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .builtYear(request.builtYear())
                .scale(request.scale())
                .inspectionCycleMonths(request.inspectionCycleMonths())
                .nextInspectionDueAt(request.nextInspectionDueAt())
                .initialGrade(request.initialGrade())
                .assigneeUserId(request.assigneeUserId())
                .memo(request.memo())
                .build();
        Facility saved = facilityRepository.save(facility);
        return FacilityResponse.from(saved);
    }

    public List<FacilityResponse> list(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Facility> facilities =
                facilityRepository.findByCompanyIdOrderByIdAsc(companyId, PageRequest.of(0, FACILITY_LIST_MAX));
        // #484 상한(500건)에 걸리면 나머지가 무고지로 잘린다(#502 P2) — 운영 감지를 위해 WARN 로그를 남긴다.
        // 응답 계약(List<FacilityResponse>)은 유지하고, 진짜 페이지네이션 전환 전까지의 임시 관측 수단이다.
        if (facilities.size() == FACILITY_LIST_MAX) {
            long actualCount = facilityRepository.countByCompanyId(companyId);
            log.warn("시설물 목록 상한({}) 도달 — companyId={} 실제 보유 {}건, 상한 초과분 응답에서 누락",
                    FACILITY_LIST_MAX, companyId, actualCount);
        }
        if (facilities.isEmpty()) {
            return List.of();
        }

        // HAJA-434 갭1 P1 픽스 — 시설물 클릭 시 하자 오버레이 직행은 list() 응답이 latestDefectId를
        // 채워야 실제로 동작한다(get()만 채우면 목록 화면에서 항상 null → 항상 폴백). 시설물별
        // findLatestIdsByFacility 반복 호출은 N+1이므로 배치 쿼리 1회로 조회한다.
        List<Long> facilityIds = facilities.stream().map(Facility::getId).toList();
        Map<Long, Long> latestDefectIdByFacilityId =
                nullToEmpty(defectRepository.findLatestByFacilityIds(facilityIds, companyId)).stream()
                        .collect(Collectors.toMap(
                                FacilityLatestDefectProjection::getFacilityId,
                                FacilityLatestDefectProjection::getDefectId,
                                // facilityId asc, createdAt desc 정렬이므로 같은 facilityId의 첫 값이 최신이다.
                                (first, second) -> first));

        // 시설물 카드 "최근 점검 MM.dd"(HAJA-514/#1074) — listStatus()가 이미 쓰는 배치 조회와 동일 패턴.
        List<Inspection> latestInspections = nullToEmpty(inspectionRepository.findLatestByFacilityIds(facilityIds));
        Map<Long, LocalDate> lastInspectedByFacilityId = latestInspections.stream()
                .collect(Collectors.toMap(Inspection::getFacilityId, Inspection::getInspectionDate));
        Map<Long, Long> latestInspectionIdByFacilityId = latestInspections.stream()
                .collect(Collectors.toMap(Inspection::getFacilityId, Inspection::getId));

        // 시설물 목록 대표 사진 썸네일(HAJA-367/#670) — 시설물별 findByFacilityIdOrderByIdAsc 반복 호출은
        // N+1이므로 배치 쿼리 1회로 조회(위 latestDefectId와 동일한 조립 패턴). 지도뷰에서 대표 사진이
        // 없는 시설물은 최신 점검의 첫 사진으로 폴백하되, 사용자가 명시로 등록한 대표 사진이 항상 우선이다.
        Map<Long, String> thumbnailUrlByFacilityId =
                nullToEmpty(mediaRepository.findFirstIdsByFacilityIds(facilityIds, companyId)).stream()
                        .collect(Collectors.toMap(
                                FacilityRepresentativeMediaProjection::getFacilityId,
                                p -> thumbnailPath(p.getMediaId()),
                                // facilityId asc, id asc 정렬이므로 같은 facilityId의 첫 값이 최초 등록 사진이다.
                                (first, second) -> first));
        Map<Long, String> inspectionThumbnailUrlByFacilityId =
                latestInspections.isEmpty() ? Map.of() :
                        nullToEmpty(mediaRepository.findFirstIdsByInspectionIds(
                                latestInspections.stream().map(Inspection::getId).toList())).stream()
                                .collect(Collectors.toMap(
                                        FacilityRepresentativeMediaProjection::getFacilityId,
                                        p -> thumbnailPath(p.getMediaId()),
                                        // inspectionIds는 시설물별 최신 점검만 넘기며, id asc 첫 값이 대표 폴백이다.
                                        (first, second) -> first));

        FacilityDefectSummary defectSummary = summarizeFacilityDefects(latestInspections);

        // 시설물 카드 하자건수 배지(HAJA-515/#1075) — 시설물별 findLatestIdsByFacility 반복 호출을 피하는
        // 것과 동일한 이유로 배치 쿼리 1회. 하자가 0건인 시설물은 결과에 로우 자체가 없으므로
        // getOrDefault(id, 0L)로 명시적으로 0을 채운다(defectCount는 non-null 필드).
        Map<Long, Long> defectCountByFacilityId =
                defectRepository.countGroupByFacilityIds(facilityIds, companyId).stream()
                        .collect(Collectors.toMap(
                                FacilityDefectCountProjection::getFacilityId,
                                FacilityDefectCountProjection::getCnt));

        return facilities.stream()
                .map(facility -> FacilityResponse.from(
                        facility,
                        latestDefectIdByFacilityId.get(facility.getId()),
                        resolveThumbnailUrl(
                                facility.getId(), thumbnailUrlByFacilityId, inspectionThumbnailUrlByFacilityId),
                        lastInspectedByFacilityId.get(facility.getId()),
                        latestInspectionIdByFacilityId.get(facility.getId()),
                        defectSummary.highestGradeByFacilityId().get(facility.getId()),
                        defectSummary.warningCountByFacilityId().getOrDefault(facility.getId(), 0L),
                        defectSummary.cautionCountByFacilityId().getOrDefault(facility.getId(), 0L),
                        defectCountByFacilityId.getOrDefault(facility.getId(), 0L)))
                .toList();
    }

    public FacilityResponse get(Long userId, Long companyId, Long facilityId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Facility facility = findCompanyFacility(companyId, facilityId);
        Long latestDefectId = nullToEmpty(defectRepository
                .findLatestIdsByFacility(facilityId, companyId, PageRequest.of(0, 1)))
                .stream()
                .findFirst()
                .orElse(null);
        String thumbnailUrl = nullToEmpty(mediaRepository.findByFacilityIdOrderByIdAsc(facilityId)).stream()
                .findFirst()
                .map(media -> thumbnailPath(media.getId()))
                .orElse(null);
        List<Inspection> latestInspections =
                nullToEmpty(inspectionRepository.findLatestByFacilityIds(List.of(facilityId)));
        LocalDate lastInspectedAt = latestInspections.stream()
                .findFirst()
                .map(Inspection::getInspectionDate)
                .orElse(null);
        Long latestInspectionId = latestInspections.stream()
                .findFirst()
                .map(Inspection::getId)
                .orElse(null);
        if (thumbnailUrl == null && latestInspectionId != null) {
            thumbnailUrl = mediaRepository.findFirstByInspectionIdOrderByIdAsc(latestInspectionId)
                    .map(media -> thumbnailPath(media.getId()))
                    .orElse(null);
        }
        FacilityDefectSummary defectSummary = summarizeFacilityDefects(latestInspections);
        long defectCount = defectRepository.countGroupByFacilityIds(List.of(facilityId), companyId).stream()
                .findFirst()
                .map(FacilityDefectCountProjection::getCnt)
                .orElse(0L);
        return FacilityResponse.from(
                facility,
                latestDefectId,
                thumbnailUrl,
                lastInspectedAt,
                latestInspectionId,
                defectSummary.highestGradeByFacilityId().get(facilityId),
                defectSummary.warningCountByFacilityId().getOrDefault(facilityId, 0L),
                defectSummary.cautionCountByFacilityId().getOrDefault(facilityId, 0L),
                defectCount);
    }

    // MediaResponse.from()과 동일한 경로 조립 — Media.thumbnailUrl(저장키)을 그대로 반환하지 않고
    // 인가된 컨트롤러 엔드포인트 경로만 노출한다.
    private static String thumbnailPath(Long mediaId) {
        return "/api/media/" + mediaId + "/thumbnail";
    }

    private static String resolveThumbnailUrl(
            Long facilityId, Map<Long, String> representativeThumbnails, Map<Long, String> inspectionThumbnails) {
        if (facilityId == null) {
            return null;
        }
        String representativeThumbnail = representativeThumbnails.get(facilityId);
        return representativeThumbnail != null ? representativeThumbnail : inspectionThumbnails.get(facilityId);
    }

    private FacilityDefectSummary summarizeFacilityDefects(List<Inspection> inspections) {
        if (inspections.isEmpty()) {
            return FacilityDefectSummary.empty();
        }
        List<Long> inspectionIds = inspections.stream().map(Inspection::getId).toList();

        Map<Long, DefectGrade> highestByFacilityId = new java.util.HashMap<>();
        Map<Long, Long> warningByFacilityId = new java.util.HashMap<>();
        Map<Long, Long> cautionByFacilityId = new java.util.HashMap<>();

        for (FacilityGradeCountProjection projection :
                nullToEmpty(defectRepository.countGroupByFacilityIdAndGrade(inspectionIds))) {
            Long facilityId = projection.getFacilityId();
            DefectGrade grade = projection.getGrade();
            long count = projection.getCnt();
            highestByFacilityId.merge(facilityId, grade, FacilityService::worseGrade);
            if (grade == DefectGrade.D || grade == DefectGrade.E) {
                warningByFacilityId.merge(facilityId, count, Long::sum);
            } else if (grade == DefectGrade.C) {
                cautionByFacilityId.merge(facilityId, count, Long::sum);
            }
        }

        Map<Long, String> highestGradeByFacilityId = highestByFacilityId.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().name()));
        return new FacilityDefectSummary(highestGradeByFacilityId, warningByFacilityId, cautionByFacilityId);
    }

    private static DefectGrade worseGrade(DefectGrade left, DefectGrade right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record FacilityDefectSummary(
            Map<Long, String> highestGradeByFacilityId,
            Map<Long, Long> warningCountByFacilityId,
            Map<Long, Long> cautionCountByFacilityId
    ) {
        static FacilityDefectSummary empty() {
            return new FacilityDefectSummary(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        }
    }

    /**
     * 시설물 현황 전용 목록(#540 ⑥, HAJA-378) — 대시보드 스타일 테이블 화면 전용 읽기 전용 조회.
     * list()와 동일한 회사 스코프·상한(#484) 정책을 재사용하되, 응답에 상태(initialGrade)·D-day·
     * 담당자명·최근 점검일을 함께 계산해 붙인다. 담당자명/최근 점검일은 N+1 을 피하기 위해
     * 배치 조회(findAllById/findLatestByFacilityIds) 후 Map 으로 조립한다
     * (DashboardService.getRecentInspections() 의 creatorNameById 패턴과 동일).
     */
    public List<FacilityStatusResponse> listStatus(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Facility> facilities =
                facilityRepository.findByCompanyIdOrderByIdAsc(companyId, PageRequest.of(0, FACILITY_LIST_MAX));
        if (facilities.size() == FACILITY_LIST_MAX) {
            long actualCount = facilityRepository.countByCompanyId(companyId);
            log.warn("시설물 현황 목록 상한({}) 도달 — companyId={} 실제 보유 {}건, 상한 초과분 응답에서 누락",
                    FACILITY_LIST_MAX, companyId, actualCount);
        }
        if (facilities.isEmpty()) {
            return List.of();
        }

        LocalDate today = LocalDate.now(KST);
        List<Long> facilityIds = facilities.stream().map(Facility::getId).toList();

        // #1136 — 최근 점검 1건을 한 번만 조회해 lastInspectedAt/inspectionType 둘 다 파생한다
        // (같은 조회를 두 번 하지 않도록 Inspection 자체를 값으로 두는 단일 맵).
        Map<Long, Inspection> latestInspectionByFacilityId =
                inspectionRepository.findLatestByFacilityIds(facilityIds).stream()
                        .collect(Collectors.toMap(Inspection::getFacilityId, inspection -> inspection));

        List<Long> assigneeIds = facilities.stream()
                .map(Facility::getAssigneeUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> assigneeNameById = assigneeIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(assigneeIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getName));

        return facilities.stream()
                .map(facility -> {
                    Inspection latestInspection = latestInspectionByFacilityId.get(facility.getId());
                    return FacilityStatusResponse.of(
                            facility,
                            today,
                            // assigneeUserId 가 null 이면 Map.of()(불변 빈 맵)의 get(null) 이 NPE 를 던지므로
                            // (ImmutableCollections 는 null 키 조회 자체를 금지) null 키는 조회 전에 걸러낸다.
                            facility.getAssigneeUserId() == null
                                    ? null : assigneeNameById.get(facility.getAssigneeUserId()),
                            latestInspection == null ? null : latestInspection.getInspectionDate(),
                            latestInspection == null ? null : latestInspection.getType());
                })
                .toList();
    }

    /**
     * 시설물 행 잠금(PESSIMISTIC_WRITE) — 호출부의 트랜잭션이 끝날 때까지 유지된다.
     * dev-05-02(점검 회차 생성)에서 같은 시설물에 대한 동시 회차 생성 요청을 직렬화해
     * round_no 채번 경쟁(unique(facility_id, round_no) 위반)을 막는 용도로 사용.
     * 호출 전 소유권 검증이 끝난 상태(시설물 존재 보장)를 전제하므로 반환값은 사용하지 않는다.
     */
    @Transactional
    public void lockForUpdate(Long facilityId) {
        facilityRepository.findByIdForUpdate(facilityId);
    }

    @Transactional
    public FacilityResponse update(Long userId, Long companyId, Long facilityId, FacilityUpdateRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Facility facility = findCompanyFacility(companyId, facilityId);
        validateAssigneeIfPresent(userId, request.assigneeUserId());
        facility.updateInfo(
                request.name(),
                request.type(),
                request.address(),
                request.latitude(),
                request.longitude(),
                request.builtYear(),
                request.scale(),
                request.inspectionCycleMonths(),
                request.nextInspectionDueAt(),
                request.initialGrade(),
                request.assigneeUserId(),
                request.memo());
        return FacilityResponse.from(facility);
    }

    @Transactional
    public void delete(Long userId, Long companyId, Long facilityId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Facility facility = findCompanyFacility(companyId, facilityId);
        // #1024 — V19(#1017)가 추가한 fk_media_facility(NO ACTION)는 시설물에 대표 사진이 남아있으면
        // 삭제 시 FK 위반(처리되지 않은 500)을 낸다. 같은 트랜잭션에서 media 로우 + 스토리지 파일을
        // 먼저 정리해 제약 위반 자체가 발생하지 않게 한다.
        deleteFacilityMedia(facilityId);
        facilityRepository.delete(facility);
        // 시설물은 물리 삭제라 보유량이 즉시 줄어든다(#843) — 사용량 표시값을 같은 트랜잭션에서 재동기화해
        // 카운터가 영구히 부풀어 신규 등록을 잘못 막는 드리프트를 막는다.
        quotaService.syncFacilityUsage(userId, companyId);
    }

    /**
     * 시설물 삭제 전 대표 사진 정리(#1024) — MediaService 를 주입하면 MediaService → FacilityService
     * 역방향 의존과 겹쳐 순환참조가 나므로(생성자 주입, {@code @Lazy} 미사용) 주입하지 않는다. 대신
     * MediaService 자신이 쓰는 것과 동일한 두 컴포넌트(MediaRepository, FileStorageService)를 직접 써서
     * 스토리지 파일을 지운 뒤 media 로우를 삭제한다. FileStorageService#delete 는 best-effort/never-throws
     * (blank/null 키는 no-op)라 개별 try/catch 없이 그대로 호출한다(MediaService.uploadMedia 의
     * {@code storedKeys.forEach(fileStorage::delete)} 와 동일 패턴).
     */
    private void deleteFacilityMedia(Long facilityId) {
        List<Media> facilityMedia = mediaRepository.findByFacilityIdOrderByIdAsc(facilityId);
        if (facilityMedia.isEmpty()) {
            return;
        }
        facilityMedia.forEach(media -> {
            fileStorage.delete(media.getOriginalUrl());
            fileStorage.delete(media.getThumbnailUrl());
            fileStorage.delete(media.getDetailUrl());
        });
        mediaRepository.deleteAll(facilityMedia);
    }

    /**
     * 점검주기 설정(dev-04-03, #268) — 회사 스코프 검증 후 엔티티 메서드로 상태전이 위임.
     * 기준일(오늘)은 서비스가 LocalDate.now() 로 산출해 엔티티에 주입한다.
     */
    @Transactional
    public FacilityResponse setSchedule(
            Long userId, Long companyId, Long facilityId, FacilityScheduleRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Facility facility = findCompanyFacility(companyId, facilityId);
        facility.updateSchedule(request.inspectionCycleMonths(), LocalDate.now());
        return FacilityResponse.from(facility);
    }

    /**
     * 점검 회차 완료(REPORTED 전이) 시 다음 점검일을 재계산한다(#1497/HAJA-656).
     * updateSchedule() 의 "최종 점검일 기준 정교화"(위 javadoc 참고) 후속 조치 —
     * baseDate 로 그 회차의 실제 점검일(inspection.inspectionDate())을 받는다.
     * 점검주기가 설정돼 있지 않으면(null/0) 재계산할 기준이 없으므로 아무것도 하지 않는다.
     *
     * <p><b>낡은 회차의 덮어쓰기 차단(#1591 P2)</b> — 회차 완료 순서와 보고서 확정 순서는 어디에서도
     * 강제되지 않는다(ReportService#markInspectionReported 는 그 회차의 상태만 본다). 그래서 3회차를
     * 먼저 확정한 뒤 밀려 있던 2회차를 확정하면, 무조건 덮어쓰기가 next_inspection_due_at 을
     * <b>과거로 되돌려</b> 점검 일정 알림·대시보드가 이미 지난 날짜를 "다음 점검일"로 표시했다.
     * 이 회차가 시설물의 <b>최신 점검일</b>일 때만 반영해서 그 역행을 막는다.
     */
    @Transactional
    public void recalculateNextInspectionDueAt(
            Long userId, Long companyId, Long facilityId, LocalDate baseDate) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Facility facility = findCompanyFacility(companyId, facilityId);
        Integer cycleMonths = facility.getInspectionCycleMonths();
        if (cycleMonths == null || cycleMonths <= 0) {
            return;
        }
        if (isStaleInspectionDate(facilityId, baseDate)) {
            return;
        }
        facility.updateSchedule(cycleMonths, baseDate);
    }

    /**
     * 이 회차의 점검일이 시설물의 최신 점검일보다 뒤처져 있는지(= 이미 더 최신 회차가 존재하는지)
     * 판정한다(#1591 P2). 판정 기준은 이미 있는
     * {@link InspectionRepository#findMaxInspectionDateByFacilityId}(#1291 회차 생성 검증용)를 재사용한다 —
     * 이 메서드가 불리는 시점엔 확정 중인 회차도 이미 저장돼 있으므로 {@code baseDate == max} 면 최신이다.
     * 회차가 하나도 조회되지 않으면(empty) 비교 기준이 없으므로 baseDate 를 최신으로 본다.
     *
     * <p><b>대안이던 {@code max(기존값, baseDate + cycle)} 대신 "최신 점검일일 때만 반영"을 택한 이유</b>:
     * <ul>
     *   <li>max() 는 next_inspection_due_at 을 <b>단조증가</b>로 고정해 버린다. 그러면 최신 회차의
     *       점검일이 뒤늦게 정정되거나(입력 오류 수정) 점검 주기를 <b>짧게</b> 바꾼 뒤 그 회차를 다시
     *       확정해도 날짜가 앞당겨지지 않고 옛 값에 눌러앉는다 — "주기를 단축했는데 일정이 안 당겨진다"는
     *       새 버그를 만든다.</li>
     *   <li>여기서 막아야 하는 건 "값이 작아지는 것"이 아니라 "<b>낡은 회차</b>가 최신 회차의 산출을
     *       덮어쓰는 것"이다. 기준을 점검일 자체에 두면 원인을 직접 차단하면서, 최신 회차의 재계산은
     *       언제나 현재 주기 기준으로 정확히 다시 나온다.</li>
     * </ul>
     * 주기 설정 화면({@link #setSchedule})은 이 가드와 무관하게 오늘 기준으로 항상 덮어쓰므로,
     * 주기 단축이 즉시 일정에 반영되는 경로도 그대로 남는다.
     */
    private boolean isStaleInspectionDate(Long facilityId, LocalDate baseDate) {
        return inspectionRepository.findMaxInspectionDateByFacilityId(facilityId)
                .map(baseDate::isBefore)
                .orElse(false);
    }

    private Facility findCompanyFacility(Long companyId, Long facilityId) {
        return facilityRepository.findByIdAndCompanyId(facilityId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND));
    }

    /**
     * 담당자 배정 검증(#628 / HAJA-347) — inspections.assigned_inspector_id와 동일하게
     * AuthService.validateAssignableInspector 를 재사용한다. 시설물 담당자는 선택 입력(nullable)이라
     * assigneeUserId 가 없으면 검증을 건너뛴다(값이 있을 때만 활성·역할·회사·멤버십을 검증).
     */
    private void validateAssigneeIfPresent(Long userId, Long assigneeUserId) {
        if (assigneeUserId != null) {
            authService.validateAssignableInspector(userId, assigneeUserId);
        }
    }
}
