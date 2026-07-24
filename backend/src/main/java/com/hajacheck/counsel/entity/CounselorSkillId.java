package com.hajacheck.counsel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@link CounselorSkill}의 복합키(상담사 식별자 + 처리 가능한 상담 유형). */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class CounselorSkillId implements Serializable {

    @Column(name = "counselor_id", nullable = false)
    private Long counselorId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "counsel_type", columnDefinition = "counsel_type", nullable = false)
    private CounselType counselType;
}
