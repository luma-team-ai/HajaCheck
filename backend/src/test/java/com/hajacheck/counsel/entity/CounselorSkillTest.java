package com.hajacheck.counsel.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CounselorSkillTest {

    @Test
    void assign_상담사와상담유형으로생성() {
        CounselorSkill skill = CounselorSkill.assign(10L, CounselType.USAGE);

        assertThat(skill.getId().getCounselorId()).isEqualTo(10L);
        assertThat(skill.getId().getCounselType()).isEqualTo(CounselType.USAGE);
    }

    @Test
    void assign_상담사식별자가없으면예외() {
        assertThatThrownBy(() -> CounselorSkill.assign(null, CounselType.USAGE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assign_상담유형이없으면예외() {
        assertThatThrownBy(() -> CounselorSkill.assign(10L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void id_동일한상담사와상담유형은동등하다() {
        CounselorSkillId first = new CounselorSkillId(10L, CounselType.USAGE);
        CounselorSkillId second = new CounselorSkillId(10L, CounselType.USAGE);
        CounselorSkillId different = new CounselorSkillId(10L, CounselType.BILLING_ETC);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(different);
    }
}
