package com.hajacheck.demo.repository;

import com.hajacheck.auth.entity.User;
import com.hajacheck.core.media.entity.Media;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 데모 리셋 전용 벌크 삭제(#1626) — <b>모든 문장이 {@code companyId} 스코프 조건을 가진다.</b>
 * destructive 쿼리를 도메인 리포지토리에 흩뿌리지 않고 여기 한곳에 모아, "companyId 조건 누락 없음"을
 * 리뷰·테스트({@code DemoSeedResetIntegrationTest})가 한 파일에서 증명할 수 있게 한다.
 *
 * <p><b>삭제 순서(FK 정합 — DDL 에 cascade 가 없는 참조를 자식부터)</b>: 조치이력/수정이력 → 하자 →
 * 보고서 → 미디어 → 점검 → 시설물 → <b>상담 메모 → 상담 티켓</b>(#1626 P2-1: counsel_tickets 는
 * users·chat_sessions 를 참조하므로 그 둘보다 먼저) → 챗메시지 → 챗세션 → 알림 →
 * <b>예약 플랜변경 → 결제</b>(#1626 P1-1/P2-1: user_plans·users 참조) → <b>비-FREE 사용량 카운터 →
 * 비-FREE user_plans</b>(#1626 P1-1 FREE 강제 정합) → 멤버십 → 사용자.
 * user_consents·inspection_notification_settings·chat_message_citations 는 DDL {@code on delete cascade}
 * 라 부모 삭제로 함께 지워진다.
 *
 * <p><b>지우지 않는 것</b>: 데모 ADMIN 계정({@code keepUserId})·회사·<b>FREE user_plan 과 그 사용량
 * 카운터</b>(#1626 P2-4: 월 누적 분석 카운터 보존 — 매일 리셋이 월 50 분석을 일 50 으로 완화하지 않게).
 * FREE 플랜이 없어졌으면 호출부가 {@code PlanProvisioningService.ensureFreePlanForCompany} 로 복원한다.
 */
public interface DemoResetRepository extends Repository<User, Long> {

    // ── 파일 회수 대상 키 수집(삭제 전에 읽어 둔다 — 행이 지워지면 키를 알 수 없다) ──

    /** 미디어 저장키(점검 소속 + 시설물 대표 사진). */
    @Query("""
            select m from Media m
            where m.inspectionId in (select i.id from Inspection i where i.facilityId in
                    (select f.id from Facility f where f.companyId = :companyId))
               or m.facilityId in (select f.id from Facility f where f.companyId = :companyId)
            """)
    List<Media> findCompanyMedia(@Param("companyId") Long companyId);

    /** 보고서 PDF URL(비어 있지 않은 것만) — 방문자가 확정한 보고서의 PDF 스토리지 키 회수용(#1626 P2-3). */
    @Query("""
            select r.pdfUrl from Report r
            where r.pdfUrl is not null
              and r.inspectionId in (select i.id from Inspection i where i.facilityId in
                    (select f.id from Facility f where f.companyId = :companyId))
            """)
    List<String> findCompanyReportPdfUrls(@Param("companyId") Long companyId);

    /** 챗 첨부 스토리지 키(비어 있지 않은 것만) — 방문자 상담/챗 첨부 파일 회수용(#1626 P2-3). */
    @Query("""
            select cm.attachmentKey from ChatMessage cm
            where cm.attachmentKey is not null
              and cm.sessionId in (select s.id from ChatSession s where s.userId in
                    (select u.id from User u where u.companyId = :companyId))
            """)
    List<String> findCompanyChatAttachmentKeys(@Param("companyId") Long companyId);

    @Query("select count(f) from Facility f where f.companyId = :companyId")
    long countFacilities(@Param("companyId") Long companyId);

    // ── 삭제(FK 순서대로) ──

    @Modifying
    @Query("""
            delete from DefectActionLog l where l.defectId in
                (select d.id from Defect d where d.inspectionId in
                    (select i.id from Inspection i where i.facilityId in
                        (select f.id from Facility f where f.companyId = :companyId)))
            """)
    int deleteDefectActionLogs(@Param("companyId") Long companyId);

    @Modifying
    @Query("""
            delete from DefectRevision r where r.defectId in
                (select d.id from Defect d where d.inspectionId in
                    (select i.id from Inspection i where i.facilityId in
                        (select f.id from Facility f where f.companyId = :companyId)))
            """)
    int deleteDefectRevisions(@Param("companyId") Long companyId);

    @Modifying
    @Query("""
            delete from Defect d where d.inspectionId in
                (select i.id from Inspection i where i.facilityId in
                    (select f.id from Facility f where f.companyId = :companyId))
            """)
    int deleteDefects(@Param("companyId") Long companyId);

    @Modifying
    @Query("""
            delete from Report r where r.inspectionId in
                (select i.id from Inspection i where i.facilityId in
                    (select f.id from Facility f where f.companyId = :companyId))
            """)
    int deleteReports(@Param("companyId") Long companyId);

    @Modifying
    @Query("""
            delete from Media m
            where m.inspectionId in (select i.id from Inspection i where i.facilityId in
                    (select f.id from Facility f where f.companyId = :companyId))
               or m.facilityId in (select f.id from Facility f where f.companyId = :companyId)
            """)
    int deleteMedia(@Param("companyId") Long companyId);

    @Modifying
    @Query("""
            delete from Inspection i where i.facilityId in
                (select f.id from Facility f where f.companyId = :companyId)
            """)
    int deleteInspections(@Param("companyId") Long companyId);

    @Modifying
    @Query("delete from Facility f where f.companyId = :companyId")
    int deleteFacilities(@Param("companyId") Long companyId);

    /**
     * 상담 메모 — counsel_tickets 의 자식(#1626 P2-1). 티켓 소유자(user_id)가 회사 사용자이거나 티켓
     * 세션이 회사 사용자 세션인 티켓의 메모를 지운다.
     */
    @Modifying
    @Query("""
            delete from CounselTicketNote n where n.ticketId in
                (select t.id from CounselTicket t where t.userId in
                        (select u.id from User u where u.companyId = :companyId)
                   or t.sessionId in (select s.id from ChatSession s where s.userId in
                        (select u2.id from User u2 where u2.companyId = :companyId)))
            """)
    int deleteCounselTicketNotes(@Param("companyId") Long companyId);

    /**
     * 상담 티켓(#1626 P2-1) — users(user_id/counselor_id)·chat_sessions(session_id) 를 참조하므로
     * 반드시 chat_sessions·users 삭제 <b>이전</b>에 지운다. 티켓 1건이라도 남으면 이후 삭제가 FK 위반으로
     * 트랜잭션 전체를 롤백시켜 리셋이 무증상 영구 실패한다(이 버그가 P2-1 의 핵심).
     */
    @Modifying
    @Query("""
            delete from CounselTicket t
            where t.userId in (select u.id from User u where u.companyId = :companyId)
               or t.sessionId in (select s.id from ChatSession s where s.userId in
                    (select u2.id from User u2 where u2.companyId = :companyId))
            """)
    int deleteCounselTickets(@Param("companyId") Long companyId);

    @Modifying
    @Query("""
            delete from ChatMessage cm where cm.sessionId in
                (select s.id from ChatSession s where s.userId in
                    (select u.id from User u where u.companyId = :companyId))
            """)
    int deleteChatMessages(@Param("companyId") Long companyId);

    @Modifying
    @Query("""
            delete from ChatSession s where s.userId in
                (select u.id from User u where u.companyId = :companyId)
            """)
    int deleteChatSessions(@Param("companyId") Long companyId);

    @Modifying
    @Query("""
            delete from Notification n where n.userId in
                (select u.id from User u where u.companyId = :companyId)
            """)
    int deleteNotifications(@Param("companyId") Long companyId);

    /**
     * 예약 플랜변경(#1626 P1-1/P2-1) — user_plans·users(requested_by) 를 참조하므로 그 둘보다 먼저.
     * 회사 구독의 모든 예약을 지운다(방문자가 건 하향 예약 정리 + FREE 강제 정합).
     */
    @Modifying
    @Query("""
            delete from ScheduledPlanChange spc where spc.userPlanId in
                (select p.id from UserPlan p where p.companyId = :companyId)
            """)
    int deleteScheduledPlanChanges(@Param("companyId") Long companyId);

    /**
     * 결제(#1626 P1-1/P2-1) — users·companies·user_plans 를 참조. 회사 구독 결제(company_id) + 회사
     * 사용자가 요청한 결제(user_id)를 모두 지운다(READY·PAID 포함 — 데모엔 실과금이 없다). 비-FREE
     * user_plans 를 지우기 전에 이 참조를 먼저 끊어야 한다.
     */
    @Modifying
    @Query("""
            delete from Payment p
            where p.companyId = :companyId
               or p.userId in (select u.id from User u where u.companyId = :companyId)
            """)
    int deletePayments(@Param("companyId") Long companyId);

    /**
     * 비-FREE 사용량 카운터(#1626 P1-1 + P2-4) — 제거할 비-FREE user_plans 의 카운터만 지운다.
     * <b>FREE 플랜의 카운터는 남긴다</b>(월 누적 분석 카운터 보존).
     */
    @Modifying
    @Query("""
            delete from UsageCounter c where c.userPlanId in
                (select p.id from UserPlan p where p.companyId = :companyId and p.planId <> :freePlanId)
            """)
    int deleteNonFreeUsageCounters(@Param("companyId") Long companyId, @Param("freePlanId") Long freePlanId);

    /**
     * 비-FREE user_plans(#1626 P1-1) — 방문자가 (가드 도입 전/우회로) 획득한 유료 구독을 제거해 FREE 로
     * 강제 정합한다. FREE 구독은 유지한다. 지운 뒤 호출부가 ensureFreePlanForCompany 로 ACTIVE FREE 존재를 보장.
     */
    @Modifying
    @Query("delete from UserPlan p where p.companyId = :companyId and p.planId <> :freePlanId")
    int deleteNonFreeUserPlans(@Param("companyId") Long companyId, @Param("freePlanId") Long freePlanId);

    /** 데모 ADMIN 본인의 멤버십은 유지한다 — 회사 스코프 판정(3요소)의 필수 조건. */
    @Modifying
    @Query("""
            delete from CompanyMembership m
            where m.companyId = :companyId and m.userId <> :keepUserId
            """)
    int deleteMembershipsExcept(@Param("companyId") Long companyId, @Param("keepUserId") Long keepUserId);

    /** 방문자가 콘솔에서 만든 사용자 삭제 — 데모 ADMIN 본인은 유지(user_consents 는 DDL cascade). */
    @Modifying
    @Query("delete from User u where u.companyId = :companyId and u.id <> :keepUserId")
    int deleteUsersExcept(@Param("companyId") Long companyId, @Param("keepUserId") Long keepUserId);
}
