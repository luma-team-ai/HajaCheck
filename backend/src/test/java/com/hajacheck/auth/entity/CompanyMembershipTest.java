package com.hajacheck.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CompanyMembershipTest {

    @Test
    void invite_신규초대_PENDING으로생성() {
        Instant expiresAt = Instant.now().plusSeconds(3600);

        CompanyMembership membership = CompanyMembership.invite(1L, 2L, 3L, expiresAt);

        assertThat(membership.getCompanyId()).isEqualTo(1L);
        assertThat(membership.getUserId()).isEqualTo(2L);
        assertThat(membership.getInvitedBy()).isEqualTo(3L);
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.PENDING);
        assertThat(membership.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(membership.getApprovedAt()).isNull();
    }

    @Test
    void approve_승인시각을기록하고유효멤버십이됨() {
        CompanyMembership membership = CompanyMembership.invite(
                1L, 2L, 3L, Instant.now().plusSeconds(3600));

        membership.approve();

        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.APPROVED);
        assertThat(membership.getApprovedAt()).isNotNull();
        assertThat(membership.isEffectiveAt(Instant.now())).isTrue();
    }

    @Test
    void revoke_회수시각을기록하고유효하지않게됨() {
        CompanyMembership membership = CompanyMembership.approvedOwner(1L, 2L);

        membership.revoke();

        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.REVOKED);
        assertThat(membership.getRevokedAt()).isNotNull();
        assertThat(membership.isEffectiveAt(Instant.now())).isFalse();
    }

    @Test
    void isEffectiveAt_만료시각이지나면유효하지않음() {
        CompanyMembership membership = CompanyMembership.invite(
                1L, 2L, 3L, Instant.now().plusSeconds(10));
        membership.approve();

        assertThat(membership.isEffectiveAt(Instant.now().plusSeconds(20))).isFalse();
    }
}
