package com.hajacheck.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailMaskerTest {

    @Test
    void mask_일반이메일_로컬1자_도메인host1자노출() {
        assertThat(EmailMasker.mask("haja@check.com")).isEqualTo("h***@c***.com");
    }

    @Test
    void mask_긴로컬파트_첫1자만노출() {
        assertThat(EmailMasker.mask("jeongjaebong@check.com")).isEqualTo("j***@c***.com");
    }

    @Test
    void mask_짧은로컬파트_한글자_그대로첫글자노출() {
        assertThat(EmailMasker.mask("ab@check.com")).isEqualTo("a***@c***.com");
    }

    @Test
    void mask_로컬파트길이1_그1글자노출() {
        assertThat(EmailMasker.mask("a@check.com")).isEqualTo("a***@c***.com");
    }

    @Test
    void mask_도메인서브도메인_마지막점기준host_tld분리() {
        assertThat(EmailMasker.mask("owner@mail.haja.com")).isEqualTo("o***@m***.com");
    }

    @Test
    void mask_짧은host_한글자_그대로첫글자노출() {
        assertThat(EmailMasker.mask("owner@a.io")).isEqualTo("o***@a***.io");
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

    @Test
    void mask_도메인에점없음_도메인전체마스킹() {
        assertThat(EmailMasker.mask("haja@localhost")).isEqualTo("h***@***");
    }

    @Test
    void mask_도메인이점으로끝남_도메인전체마스킹() {
        assertThat(EmailMasker.mask("haja@check.")).isEqualTo("h***@***");
    }

    @Test
    void mask_로컬파트비고at만있음_전체마스킹() {
        assertThat(EmailMasker.mask("@")).isEqualTo("***");
    }

    @Test
    void mask_blank_그대로반환() {
        assertThat(EmailMasker.mask("   ")).isEqualTo("   ");
    }
}
