package com.hajacheck.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    void approve_회수된멤버십에서재호출하면예외() {
        CompanyMembership membership = CompanyMembership.approvedOwner(1L, 2L);
        membership.revoke();

        assertThatThrownBy(membership::approve).isInstanceOf(IllegalStateException.class);
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.REVOKED);
    }

    @Test
    void revoke_승인되지않은멤버십에서호출하면예외() {
        CompanyMembership membership = CompanyMembership.invite(
                1L, 2L, 3L, Instant.now().plusSeconds(3600));

        assertThatThrownBy(membership::revoke).isInstanceOf(IllegalStateException.class);
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.PENDING);
    }

    @Test
    void reject_이미거절된멤버십에서재호출하면예외() {
        CompanyMembership membership = CompanyMembership.invite(
                1L, 2L, 3L, Instant.now().plusSeconds(3600));
        membership.reject();

        assertThatThrownBy(membership::reject).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reinvite_회수된멤버십행을대기상태로재사용() {
        CompanyMembership membership = CompanyMembership.approvedOwner(1L, 2L);
        membership.revoke();
        Instant newExpiresAt = Instant.now().plusSeconds(7200);

        membership.reinvite(4L, newExpiresAt);

        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.PENDING);
        assertThat(membership.getInvitedBy()).isEqualTo(4L);
        assertThat(membership.getExpiresAt()).isEqualTo(newExpiresAt);
        assertThat(membership.getApprovedAt()).isNull();
        assertThat(membership.getRevokedAt()).isNull();
        assertThat(membership.isEffectiveAt(Instant.now())).isFalse();
    }

    @Test
    void reinvite_거절또는만료된멤버십에서도가능() {
        CompanyMembership rejected = CompanyMembership.invite(
                1L, 2L, 3L, Instant.now().plusSeconds(3600));
        rejected.reject();
        CompanyMembership expired = CompanyMembership.invite(
                1L, 3L, 4L, Instant.now().plusSeconds(3600));
        expired.expire();

        rejected.reinvite(5L, null);
        expired.reinvite(5L, null);

        assertThat(rejected.getStatus()).isEqualTo(CompanyMembershipStatus.PENDING);
        assertThat(expired.getStatus()).isEqualTo(CompanyMembershipStatus.PENDING);
    }

    @Test
    void reinvite_대기또는승인상태에서는예외() {
        CompanyMembership pending = CompanyMembership.invite(
                1L, 2L, 3L, Instant.now().plusSeconds(3600));
        CompanyMembership approved = CompanyMembership.approvedOwner(1L, 3L);

        assertThatThrownBy(() -> pending.reinvite(4L, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approved.reinvite(4L, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invite_이미지난만료시각이면예외() {
        assertThatThrownBy(() -> CompanyMembership.invite(
                1L, 2L, 3L, Instant.now().minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approve_승인전에만료된초대이면대기상태를유지하고예외() {
        CompanyMembership membership = CompanyMembership.invite(
                1L, 2L, 3L, Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(membership, "expiresAt", Instant.now().minusSeconds(1));

        assertThatThrownBy(membership::approve)
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.PENDING);
        assertThat(membership.getApprovedAt()).isNull();
    }

    @Test
    void reinvite_이미지난만료시각이면기존회수상태를유지하고예외() {
        CompanyMembership membership = CompanyMembership.approvedOwner(1L, 2L);
        membership.revoke();

        assertThatThrownBy(() -> membership.reinvite(4L, Instant.now().minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.REVOKED);
        assertThat(membership.getInvitedBy()).isNull();
        assertThat(membership.getRevokedAt()).isNotNull();
    }
}
