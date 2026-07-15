package com.hajacheck.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailMaskerTest {

    @Test
    void mask_일반이메일_앞4자노출후마스킹() {
        assertThat(EmailMasker.mask("haja@check.com")).isEqualTo("haja***@check.com");
    }

    @Test
    void mask_긴로컬파트_앞4자만노출() {
        assertThat(EmailMasker.mask("jeongjaebong@check.com")).isEqualTo("jeon***@check.com");
    }

    @Test
    void mask_짧은로컬파트_전체노출후마스킹() {
        assertThat(EmailMasker.mask("ab@check.com")).isEqualTo("ab***@check.com");
    }

    @Test
    void mask_at없음_전체마스킹() {
        assertThat(EmailMasker.mask("noatsign")).isEqualTo("***");
    }

    @Test
    void mask_null_그대로반환() {
        assertThat(EmailMasker.mask(null)).isNull();
    }

    @Test
    void mask_at으로시작_전체마스킹() {
        assertThat(EmailMasker.mask("@check.com")).isEqualTo("***");
    }
}
