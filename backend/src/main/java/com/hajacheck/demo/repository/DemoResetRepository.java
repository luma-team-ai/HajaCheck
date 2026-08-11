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
 * 리뷰·테스트({@code DemoResetCompanyScopeTest})가 한 파일에서 증명할 수 있게 한다.
 *
 * <p>삭제 순서(FK 정합 — DDL 에 cascade 가 없는 참조를 자식부터): 조치이력/수정이력 → 하자 → 보고서 →
 * 미디어 → 점검 → 시설물 → 챗메시지 → 챗세션 → 알림 → 멤버십 → 사용자 → 사용량 카운터.
 * user_consents·inspection_notification_settings·chat_message_citations 는 DDL {@code on delete cascade}
 * 라 부모 삭제로 함께 지워진다.
 *
 * <p>지우지 않는 것: 데모 ADMIN 계정({@code keepUserId})·회사·플랜(user_plans)·payments·
 * scheduled_plan_changes — 계정·회사·플랜 유지는 handoff 요구이고, 결제 이력은 FK 상 demo ADMIN(유지
 * 대상)만 참조하므로 삭제 대상 사용자를 막지 않는다. 사용량 카운터는 행을 지운다 — 다음 쿼터 검사가
 * {@code QuotaService#ensurePeriodRow} 로 실측값에서 재생성하므로, 리셋 후 상태가 자동으로 정합해진다.
 */
public interface DemoResetRepository extends Repository<User, Long> {

    /** 저장 파일 회수용 — 삭제 전에 회사 스코프 미디어(점검 소속 + 시설물 대표 사진)를 통째로 읽는다. */
    @Query("""
            select m from Media m
            where m.inspectionId in (select i.id from Inspection i where i.facilityId in
                    (select f.id from Facility f where f.companyId = :companyId))
               or m.facilityId in (select f.id from Facility f where f.companyId = :companyId)
            """)
    List<Media> findCompanyMedia(@Param("companyId") Long companyId);

    @Query("select count(f) from Facility f where f.companyId = :companyId")
    long countFacilities(@Param("companyId") Long companyId);

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

    /**
     * 사용량 카운터 행 삭제 — 방문자가 소모한 분석 쿼터·부풀린 시설물/좌석 미러를 통째로 비운다.
     * 다음 쿼터 연산이 실측 기반으로 재생성한다(클래스 javadoc).
     */
    @Modifying
    @Query("""
            delete from UsageCounter c where c.userPlanId in
                (select p.id from UserPlan p where p.companyId = :companyId)
            """)
    int deleteUsageCounters(@Param("companyId") Long companyId);
}
