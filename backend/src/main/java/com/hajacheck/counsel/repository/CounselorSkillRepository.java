package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.entity.CounselorSkill;
import com.hajacheck.counsel.entity.CounselorSkillId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CounselorSkillRepository extends JpaRepository<CounselorSkill, CounselorSkillId> {

    // 대기열 스킬 필터(#1019/HAJA-501) — 해당 상담사가 보유한 counselType 전부. 별도 조인 없이
    // counselType 값만 뽑아 CounselTicketRepository 쪽 IN 절에 바로 사용한다(N+1 방지).
    @Query("select cs.id.counselType from CounselorSkill cs where cs.id.counselorId = :counselorId")
    List<CounselType> findCounselTypesByCounselorId(@Param("counselorId") Long counselorId);

    // 플랫폼 관리자 스킬 변경(#1001, HAJA-495) — "변경 내용 저장"이 항상 전체 교체(replace)라
    // 새 스킬을 insert하기 전에 기존 배정을 지운다. flushAutomatically로 같은 트랜잭션 내
    // 이후 save()보다 먼저 DELETE가 실행되도록 강제한다(기본 flush 순서는 insert가 먼저라 방치하면
    // 동일 PK 재배정 시나리오에서 순서가 꼬일 수 있다).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CounselorSkill cs where cs.id.counselorId = :counselorId")
    void deleteByCounselorId(@Param("counselorId") Long counselorId);
}
