package com.hajacheck.counsel.repository;

import com.hajacheck.counsel.entity.CounselorSkill;
import com.hajacheck.counsel.entity.CounselorSkillId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounselorSkillRepository extends JpaRepository<CounselorSkill, CounselorSkillId> {
}
