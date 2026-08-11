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
 * <p><b>대상 특정의 3중 안전장치</b> — destructive 배치가 타 회사 데이터를 건드리지 않도록:
 * <ol>
 *   <li>회사는 <b>데모 loginId 계정의 companyId</b> 로만 특정한다(외부 입력 없음).</li>
 *   <li>그 회사의 <b>owner 가 데모 계정 본인인지</b> 대조한다 — 설정 실수로 loginId 가 실사용
 *       계정을 가리키면(= 그 계정 회사가 통째로 증발할 뻔한 상황) 아무것도 지우지 않고 중단한다.</li>
 *   <li>시설물 수가 {@link DemoResetProperties#getMaxFacilitiesPerReset()} 을 넘으면 사고 신호로 보고
 *       아무것도 지우지 않고 중단한다(PlanExpiryScheduler maxPerRun 과 동일 취지).</li>
 * </ol>
 * 모든 삭제 문장 자체도 companyId 조건을 갖는다({@link DemoResetRepository} — 테스트로 격리 증명).
 *
 * <p><b>트랜잭션</b>: 삭제 + 재시드가 한 트랜잭션이다 — 재시드가 실패하면 삭제까지 롤백돼 "지워졌는데
 * 복원은 안 된" 반쪽 상태를 남기지 않는다. 파일 삭제는 커밋 후 best-effort(호출부) — DB 가 진실
 * 소스이고 고아 파일은 다음 리셋에서 문제되지 않는다(파일 삭제 실패가 DB 롤백을 유발하면 안 된다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoResetService {

    private final DemoProperties demoProperties;
    private final DemoResetProperties resetProperties;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final DemoResetRepository resetRepository;
    private final DemoSeedService demoSeedService;
    private final FileStorageService fileStorage;

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
        long facilityCount = resetRepository.countFacilities(companyId);
        if (facilityCount > resetProperties.getMaxFacilitiesPerReset()) {
            log.error("데모 리셋 중단 — 시설물 {}건이 1회 상한 {}건을 초과했다(삭제 0건). 정상 데모 사용 범위를 "
                            + "벗어난 상태라 원인 확인 전에는 지우지 않는다 (companyId={})",
                    facilityCount, resetProperties.getMaxFacilitiesPerReset(), companyId);
            return List.of();
        }

        // 삭제 전에 회수 대상 파일 키를 확보한다(행이 지워지면 키를 알 수 없다).
        List<String> storageKeys = collectStorageKeys(companyId);

        int actionLogs = resetRepository.deleteDefectActionLogs(companyId);
        int revisions = resetRepository.deleteDefectRevisions(companyId);
        int defects = resetRepository.deleteDefects(companyId);
        int reports = resetRepository.deleteReports(companyId);
        int media = resetRepository.deleteMedia(companyId);
        int inspections = resetRepository.deleteInspections(companyId);
        int facilities = resetRepository.deleteFacilities(companyId);
        int chatMessages = resetRepository.deleteChatMessages(companyId);
        int chatSessions = resetRepository.deleteChatSessions(companyId);
        int notifications = resetRepository.deleteNotifications(companyId);
        int memberships = resetRepository.deleteMembershipsExcept(companyId, demoAdmin.getId());
        int users = resetRepository.deleteUsersExcept(companyId, demoAdmin.getId());
        int usageCounters = resetRepository.deleteUsageCounters(companyId);

        // 같은 트랜잭션에서 시드 복원 — 실패 시 삭제까지 통째로 롤백된다(클래스 javadoc).
        demoSeedService.seedContent(companyId, demoAdmin.getId());

        log.info("데모 리셋 완료 — companyId={} 삭제(조치이력 {} 수정이력 {} 하자 {} 보고서 {} 미디어 {} 점검 {} "
                        + "시설물 {} 챗메시지 {} 챗세션 {} 알림 {} 멤버십 {} 사용자 {} 카운터 {}) 후 시드 복원",
                companyId, actionLogs, revisions, defects, reports, media, inspections, facilities,
                chatMessages, chatSessions, notifications, memberships, users, usageCounters);
        return storageKeys;
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
        return keys;
    }

    private void addKey(List<String> keys, String key) {
        if (key != null && !key.isBlank()) {
            keys.add(key);
        }
    }
}
