package com.hajacheck.demo.service;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.demo.config.DemoResetProperties;
import com.hajacheck.demo.repository.DemoResetRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.service.PlanProvisioningService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 데이터 리셋(#1626) — <b>데모 회사 companyId 스코프의 데이터만</b> 삭제하고
 * {@link DemoSeedService#seedContent} 로 시드 상태를 복원한다(삭제·시드가 코드 경로를 공유).
 *
 * <p><b>대상 특정의 4중 안전장치</b>(#1626 P1-2 강화) — destructive 배치가 타 회사 데이터를 건드리지 않도록:
 * <ol>
 *   <li>회사는 <b>데모 loginId 계정의 companyId</b> 로만 특정한다(외부 입력 없음).</li>
 *   <li>그 회사의 <b>owner 가 데모 계정 본인인지</b> 대조한다 — 설정 실수로 loginId 가 실사용
 *       계정을 가리키면(= 그 계정 회사가 통째로 증발할 뻔한 상황) 아무것도 지우지 않고 중단한다.</li>
 *   <li><b>데모 회사 provenance</b>(#1626 P1-2) — 회사 BRN 이 데모 시드 상수
 *       ({@link DemoSeedService#DEMO_BUSINESS_NUMBER})와 일치하고, {@code business_registration_ocr_raw}
 *       에 데모 표식({@link DemoSeedService#DEMO_SEED_PROVENANCE_MARKER})이 있어야 한다. loginId 는
 *       <b>사람이 설정으로 바꾸지만</b> BRN·provenance 는 <b>시더만 기록</b>하므로, "owner 일치하지만
 *       데모 회사가 아닌" 실사용 회사(loginId 오설정)를 이 조건이 걸러낸다. 하나라도 불일치면 삭제 0건 중단.</li>
 *   <li>시설물 수가 {@link DemoResetProperties#getMaxFacilitiesPerReset()} 을 넘으면 사고 신호로 보고
 *       아무것도 지우지 않고 중단한다(PlanExpiryScheduler maxPerRun 과 동일 취지).</li>
 * </ol>
 * 모든 삭제 문장 자체도 companyId 조건을 갖는다({@link DemoResetRepository} — 테스트로 격리 증명).
 *
 * <p><b>FREE 강제 정합</b>(#1626 P1-1 심층방어): 비-FREE user_plans·예약·결제를 정리하고
 * {@link PlanProvisioningService#ensureFreePlanForCompany}로 ACTIVE FREE 구독을 보장한다 — 결제 가드가
 * 뚫렸거나 가드 도입 전 데이터로 유료 플랜이 남아 있어도 매 회차 FREE 로 되돌린다. FREE 플랜의 사용량
 * 카운터(월 누적 분석)는 보존한다(#1626 P2-4).
 *
 * <p><b>트랜잭션</b>: 삭제 + 재시드가 한 트랜잭션이다 — 재시드가 실패하면 삭제까지 롤백돼 "지워졌는데
 * 복원은 안 된" 반쪽 상태를 남기지 않는다. 파일 삭제는 커밋 후 best-effort(호출부) — DB 가 진실
 * 소스이고 고아 파일은 다음 리셋에서 문제되지 않는다(파일 삭제 실패가 DB 롤백을 유발하면 안 된다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoResetService {

    // 보고서 PDF URL 형식(REPORT_PDF_URL_INVALID 계약): /api/reports/{id}/pdf/{storageKey}.
    // 파일 회수 시 이 접두 뒤의 저장키만 뽑아 FileStorageService#delete 에 넘긴다(#1626 P2-3).
    private static final String REPORT_PDF_MARKER = "/pdf/";

    private final DemoProperties demoProperties;
    private final DemoResetProperties resetProperties;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final DemoResetRepository resetRepository;
    private final DemoSeedService demoSeedService;
    private final FileStorageService fileStorage;
    private final PlanRepository planRepository;
    private final PlanProvisioningService planProvisioningService;

    /**
     * 리셋 실행 — 삭제한 미디어의 저장키 목록을 반환한다(호출부가 커밋 후 파일을 best-effort 삭제).
     * 대상 특정 실패(데모 계정 없음/owner 불일치/상한 초과)는 삭제 0건으로 조용히 중단하고 빈 목록을
     * 반환한다(사유는 로그).
     */
    @Transactional
    public List<String> resetToSeedState() {
        User demoAdmin = userRepository.findByEmail(demoProperties.getLoginId()).orElse(null);
        if (demoAdmin == null) {
            log.warn("데모 리셋 스킵 — 데모 계정 미존재 (loginId={}). 시드가 먼저 실행돼야 한다", demoProperties.getLoginId());
            return List.of();
        }
        Long companyId = demoAdmin.getCompanyId();
        if (companyId == null) {
            log.warn("데모 리셋 스킵 — 데모 계정에 회사가 없다 (userId={})", demoAdmin.getId());
            return List.of();
        }
        Company company = companyRepository.findById(companyId).orElse(null);
        if (company == null || !demoAdmin.getId().equals(company.getOwnerUserId())) {
            // 설정 실수 방어 — 데모 loginId 가 남의 회사 소속 계정을 가리키고 있다. 절대 지우지 않는다.
            log.error("데모 리셋 중단 — 회사 owner 가 데모 계정이 아니다 (companyId={} ownerUserId={} demoUserId={}). "
                            + "app.demo.login-id 설정이 실사용 계정을 가리키는지 확인할 것",
                    companyId, company == null ? null : company.getOwnerUserId(), demoAdmin.getId());
            return List.of();
        }
        // 데모 회사 provenance 검증(#1626 P1-2) — owner 일치만으로는 부족하다. 모든 회사는 생성자=owner라
        // loginId 에 실사용 owner 이메일이 들어가면 owner 체크를 통과한다. BRN·provenance 는 시더만
        // 기록하므로, 그 둘이 시드 상수와 일치해야만 "진짜 데모 회사"로 인정하고 삭제를 진행한다.
        if (!isDemoProvenance(company)) {
            log.error("데모 리셋 중단 — provenance 불일치(데모 회사가 아님). companyId={} brnMatch={} markerPresent={}. "
                            + "app.demo.login-id 설정이 실사용 회사를 가리키는지 확인할 것 (BRN·표식은 시더만 기록)",
                    companyId,
                    DemoSeedService.DEMO_BUSINESS_NUMBER.equals(company.getBusinessRegistrationNumber()),
                    hasDemoMarker(company));
            return List.of();
        }
        long facilityCount = resetRepository.countFacilities(companyId);
        if (facilityCount > resetProperties.getMaxFacilitiesPerReset()) {
            log.error("데모 리셋 중단 — 시설물 {}건이 1회 상한 {}건을 초과했다(삭제 0건). 정상 데모 사용 범위를 "
                            + "벗어난 상태라 원인 확인 전에는 지우지 않는다 (companyId={})",
                    facilityCount, resetProperties.getMaxFacilitiesPerReset(), companyId);
            return List.of();
        }

        // 삭제 전에 회수 대상 파일 키를 확보한다(행이 지워지면 키를 알 수 없다).
        List<String> storageKeys = collectStorageKeys(companyId);

        // FREE 강제 정합용 기준 — 없으면(plans 시드 오류) FREE 정리를 건너뛰되 나머지는 진행한다.
        Long freePlanId = planRepository.findByName(PlanName.FREE).map(Plan::getId).orElse(null);

        // FK 순서대로 삭제(자식 → 부모). 상담 티켓/메모는 chat_sessions·users 참조라 그 둘보다 먼저,
        // 예약·결제는 user_plans·users 참조라 비-FREE plans·users 보다 먼저 지운다(#1626 P2-1).
        int actionLogs = resetRepository.deleteDefectActionLogs(companyId);
        int revisions = resetRepository.deleteDefectRevisions(companyId);
        int defects = resetRepository.deleteDefects(companyId);
        int reports = resetRepository.deleteReports(companyId);
        int media = resetRepository.deleteMedia(companyId);
        int inspections = resetRepository.deleteInspections(companyId);
        int facilities = resetRepository.deleteFacilities(companyId);
        int ticketNotes = resetRepository.deleteCounselTicketNotes(companyId);
        int tickets = resetRepository.deleteCounselTickets(companyId);
        int chatMessages = resetRepository.deleteChatMessages(companyId);
        int chatSessions = resetRepository.deleteChatSessions(companyId);
        int notifications = resetRepository.deleteNotifications(companyId);
        int scheduledChanges = resetRepository.deleteScheduledPlanChanges(companyId);
        int payments = resetRepository.deletePayments(companyId);
        int nonFreeCounters = 0;
        int nonFreePlans = 0;
        if (freePlanId != null) {
            // FREE 카운터는 남긴다(#1626 P2-4: 월 누적 분석 카운터 보존). 비-FREE 것만 지운 뒤 그 plans 제거.
            nonFreeCounters = resetRepository.deleteNonFreeUsageCounters(companyId, freePlanId);
            nonFreePlans = resetRepository.deleteNonFreeUserPlans(companyId, freePlanId);
        }
        int memberships = resetRepository.deleteMembershipsExcept(companyId, demoAdmin.getId());
        int users = resetRepository.deleteUsersExcept(companyId, demoAdmin.getId());

        // FREE 강제 정합 — 비-FREE 를 지운 뒤 ACTIVE FREE 가 없으면 복원(멱등: 이미 있으면 no-op).
        planProvisioningService.ensureFreePlanForCompany(companyId);

        // 같은 트랜잭션에서 시드 복원 — 실패 시 삭제까지 통째로 롤백된다(클래스 javadoc).
        demoSeedService.seedContent(companyId, demoAdmin.getId());

        log.info("데모 리셋 완료 — companyId={} 삭제(조치이력 {} 수정이력 {} 하자 {} 보고서 {} 미디어 {} 점검 {} "
                        + "시설물 {} 상담메모 {} 상담티켓 {} 챗메시지 {} 챗세션 {} 알림 {} 예약 {} 결제 {} "
                        + "비FREE카운터 {} 비FREE플랜 {} 멤버십 {} 사용자 {}) 후 FREE 정합·시드 복원",
                companyId, actionLogs, revisions, defects, reports, media, inspections, facilities,
                ticketNotes, tickets, chatMessages, chatSessions, notifications, scheduledChanges, payments,
                nonFreeCounters, nonFreePlans, memberships, users);
        return storageKeys;
    }

    /** 데모 회사 provenance — BRN 이 시드 상수와 일치 AND ocr_raw 에 데모 표식 존재(둘 다 시더만 기록). */
    private boolean isDemoProvenance(Company company) {
        return DemoSeedService.DEMO_BUSINESS_NUMBER.equals(company.getBusinessRegistrationNumber())
                && hasDemoMarker(company);
    }

    private boolean hasDemoMarker(Company company) {
        String ocrRaw = company.getBusinessRegistrationOcrRaw();
        return ocrRaw != null && ocrRaw.contains(DemoSeedService.DEMO_SEED_PROVENANCE_MARKER);
    }

    /** 커밋 후 파일 회수(best-effort) — {@code FileStorageService#delete} 는 실패해도 예외를 던지지 않는다. */
    public void deleteStoredFiles(List<String> storageKeys) {
        storageKeys.forEach(fileStorage::delete);
        if (!storageKeys.isEmpty()) {
            log.info("데모 리셋 파일 회수 — {}건 삭제 시도(best-effort)", storageKeys.size());
        }
    }

    private List<String> collectStorageKeys(Long companyId) {
        List<String> keys = new ArrayList<>();
        for (Media media : resetRepository.findCompanyMedia(companyId)) {
            addKey(keys, media.getOriginalUrl());
            addKey(keys, media.getThumbnailUrl());
            addKey(keys, media.getDetailUrl());
        }
        // 보고서 PDF(#1626 P2-3) — pdf_url 은 /api/reports/{id}/pdf/{storageKey} 형식이라 저장키만 뽑는다.
        for (String pdfUrl : resetRepository.findCompanyReportPdfUrls(companyId)) {
            addKey(keys, extractReportPdfStorageKey(pdfUrl));
        }
        // 챗 첨부(#1626 P2-3) — attachment_key 는 그 자체가 저장키다.
        for (String attachmentKey : resetRepository.findCompanyChatAttachmentKeys(companyId)) {
            addKey(keys, attachmentKey);
        }
        return keys;
    }

    /**
     * 보고서 PDF URL 에서 저장키를 뽑는다 — {@code /pdf/} 뒤 세그먼트. 형식이 맞지 않으면 null 을 돌려
     * <b>임의 문자열을 삭제 대상으로 넘기지 않는다</b>(오삭제 방지). 저장키에 슬래시가 없다는 전제라
     * lastIndexOf 로 마지막 {@code /pdf/} 뒤를 취한다.
     */
    private String extractReportPdfStorageKey(String pdfUrl) {
        if (pdfUrl == null) {
            return null;
        }
        int idx = pdfUrl.lastIndexOf(REPORT_PDF_MARKER);
        if (idx < 0) {
            return null;
        }
        String key = pdfUrl.substring(idx + REPORT_PDF_MARKER.length());
        return key.isBlank() ? null : key;
    }

    private void addKey(List<String> keys, String key) {
        if (key != null && !key.isBlank()) {
            keys.add(key);
        }
    }
}
