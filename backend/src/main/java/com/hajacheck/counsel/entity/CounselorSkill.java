package com.hajacheck.counsel.entity;

import com.hajacheck.global.exception.DomainValidationException;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 상담사(users, role=COUNSELOR)가 처리 가능한 상담 유형을 나타내는 다대다 매핑. */
@Entity
@Getter
@Table(name = "counselor_skills")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CounselorSkill {

    @EmbeddedId
    private CounselorSkillId id;

    @Builder(access = AccessLevel.PRIVATE)
    private CounselorSkill(CounselorSkillId id) {
        this.id = id;
    }

    public static CounselorSkill assign(Long counselorId, CounselType counselType) {
        if (counselorId == null) {
            throw new DomainValidationException("assign 불가: 상담사 식별자는 필수다");
        }
        if (counselType == null) {
            throw new DomainValidationException("assign 불가: 상담 유형은 필수다");
        }
        return CounselorSkill.builder()
                .id(new CounselorSkillId(counselorId, counselType))
                .build();
    }
}
