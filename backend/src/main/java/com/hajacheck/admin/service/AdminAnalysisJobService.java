package com.hajacheck.admin.service;

import com.hajacheck.admin.dto.AdminAnalysisJobItem;
import com.hajacheck.admin.dto.AdminAnalysisJobStatus;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 AI 분석 현황 모니터링(신규) — 같은 회사 소속 검사자(inspector)들의 AI 분석 작업이 지금
 * 진행 중인지/완료됐는지를 관리자(ADMIN, company 스코프)가 한눈에 보는 화면.
 *
 * <p>목록 조회 자체는 InspectionRepositoryCustom#findRecentInspectionsPage(대시보드 "최근 점검
 * 전체보기"가 이미 쓰는 회사 스코프·상태 필터·페이징 쿼리)를 그대로 재사용한다 — 이 화면에 필요한
 * "회사 스코프 + 상태 IN 필터 + 페이징"이 이미 정확히 그 메서드의 계약이라 새 Criteria 쿼리를
 * 따로 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAnalysisJobService {

    private final InspectionRepository inspectionRepository;
    private final UserRepository userRepository;
    private final FacilityRepository facilityRepository;
    private final AnalysisProgressStore analysisProgressStore;

    public PageResponse<AdminAnalysisJobItem> list(Long companyId, AdminAnalysisJobStatus statusFilter,
                                                     Pageable pageable) {
        requireCompanyId(companyId);

        // null/empty = 상태 필터 없음(findRecentInspectionsPage 계약, InspectionRepositoryCustom 참고) —
        // DashboardService.searchRecentInspections와 동일하게 빈 Set으로 통일한다(null 대신).
        Collection<InspectionStatus> statuses = statusFilter == null ? Set.of() : statusFilter.toInspectionStatuses();

        Page<Inspection> page = inspectionRepository.findRecentInspectionsPage(
                companyId, null, null, statuses, null, List.of(), pageable);

        List<Inspection> content = page.getContent();
        if (content.isEmpty()) {
            return new PageResponse<>(List.of(), page.getNumber(), page.getTotalElements());
        }

        Map<Long, String> facilityNameById = facilityRepository
                .findAllById(content.stream().map(Inspection::getFacilityId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Facility::getId, Facility::getName));

        Map<Long, String> inspectorNameById = userRepository
                .findAllById(content.stream().map(Inspection::getAssignedInspectorId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        List<AdminAnalysisJobItem> items = content.stream()
                .map(inspection -> toItem(inspection, facilityNameById, inspectorNameById))
                .toList();

        return new PageResponse<>(items, page.getNumber(), page.getTotalElements());
    }

    private AdminAnalysisJobItem toItem(
            Inspection inspection, Map<Long, String> facilityNameById, Map<Long, String> inspectorNameById) {
        AdminAnalysisJobStatus status = AdminAnalysisJobStatus.from(inspection.getStatus());
        // 진행률은 ANALYZING 건에 한해서만 조회한다 — 나머지 상태는 어차피 진행률 개념이 없고,
        // 페이지당 최대 size건으로 조회 범위가 이미 좁아 N+1이어도 무해하다(회사별 동시분석 자체가
        // 서버 스레드풀 제약(analysisTaskExecutor core=max=2)상 많지 않다).
        Integer progressPercent = status == AdminAnalysisJobStatus.ANALYZING
                ? analysisProgressStore.find(inspection.getId())
                        .map(AnalysisStatusResponse::progressPercent)
                        .orElse(null)
                : null;

        return new AdminAnalysisJobItem(
                inspection.getId(),
                facilityNameById.get(inspection.getFacilityId()),
                inspection.getAssignedInspectorId(),
                inspectorNameById.get(inspection.getAssignedInspectorId()),
                inspection.getInspectionDate(),
                status,
                progressPercent);
    }

    // AdminUserService.requireCompanyId와 동일 원칙 — 이 화면은 기업 관리자 전용이라 companyId
    // 없는 관리자(플랫폼 관리자 등)는 접근 대상이 아니다.
    private void requireCompanyId(Long companyId) {
        if (companyId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
