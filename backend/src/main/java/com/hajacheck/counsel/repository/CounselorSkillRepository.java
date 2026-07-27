package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.entity.CounselorSkill;
import com.hajacheck.counsel.entity.CounselorSkillId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CounselorSkillRepository extends JpaRepository<CounselorSkill, CounselorSkillId> {

    // 대기열 스킬 필터(#1019/HAJA-501) — 해당 상담사가 보유한 counselType 전부. 별도 조인 없이
    // counselType 값만 뽑아 CounselTicketRepository 쪽 IN 절에 바로 사용한다(N+1 방지).
    @Query("select cs.id.counselType from CounselorSkill cs where cs.id.counselorId = :counselorId")
    List<CounselType> findCounselTypesByCounselorId(@Param("counselorId") Long counselorId);
}
