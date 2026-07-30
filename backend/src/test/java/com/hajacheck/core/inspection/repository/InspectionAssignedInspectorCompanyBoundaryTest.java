package com.hajacheck.core.inspection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;

/**
 * trg_inspections_check_assigned_inspector_company(HAJA-25 P2 DB 레벨 방어)가 실제 PostgreSQL에서
 * 회사 경계를 강제하는지 런타임 INSERT로 검증한다. verify.sql은 트리거의 구조적 존재만 확인하므로,
 * 이 테스트는 실제 거부/허용 동작을 별도로 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class InspectionAssignedInspectorCompanyBoundaryTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;

    @Autowired
    private InspectionRepository inspectionRepository;

    @Autowired
    private TestEntityManager em;

    private Long seedFacility(Long companyId, String name) {
        Facility facility = Facility.builder().companyId(companyId).name(name).type("BUILDING").build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private User seedApprovedCompanyOwner(String email, String companyName, String businessRegistrationNumber) {
        User owner = userRepository.saveAndFlush(
                User.createCompanyOwner(email, "owner", "<password-hash-placeholder>"));
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), companyName, businessRegistrationNumber, "owner", "Seoul", null,
                "https://files.example/registration.pdf", "{\"source\":\"TEST\"}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.saveAndFlush(company);
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        return userRepository.saveAndFlush(owner);
    }

    private User seedApprovedMember(Long companyId, String email, Role role, UserStatus status) {
        User member = userRepository.saveAndFlush(User.builder()
                .email(email)
                .name("member")
                .role(role)
                .passwordHash("<password-hash-placeholder>")
                .status(status)
                .build());
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(companyId, member.getId()));
        member.assignToCompany(companyId);
        return userRepository.saveAndFlush(member);
    }

    private Inspection newInspection(Long facilityId, Long createdBy, Long assignedInspectorId) {
        return Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 17))
                .status(InspectionStatus.CREATED)
                .build();
    }

    @Test
    void crossCompanyAssignment_rejectedByTrigger() {
        User ownerA = seedApprovedCompanyOwner(
                "boundary-owner-a@haja.test", "boundary company A", "HA25-BOUNDARY-A");
        User ownerB = seedApprovedCompanyOwner(
                "boundary-owner-b@haja.test", "boundary company B", "HA25-BOUNDARY-B");
        User inspectorB = seedApprovedMember(
                ownerB.getCompanyId(), "boundary-inspector-b@haja.test", Role.INSPECTOR, UserStatus.ACTIVE);
        Long facilityId = seedFacility(ownerA.getCompanyId(), "경계테스트빌딩A");

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(
                newInspection(facilityId, ownerA.getId(), inspectorB.getId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("effective membership");
    }

    @Test
    void sameCompanyDifferentUsersAssignment_allowed() {
        User owner = seedApprovedCompanyOwner(
                "boundary-owner-c@haja.test", "boundary company C", "HA25-BOUNDARY-C");
        User member = seedApprovedMember(
                owner.getCompanyId(), "boundary-member-c@haja.test", Role.INSPECTOR, UserStatus.ACTIVE);
        Long facilityId = seedFacility(owner.getCompanyId(), "동일회사빌딩");

        Inspection saved = inspectionRepository.saveAndFlush(
                newInspection(facilityId, owner.getId(), member.getId()));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void bothCompanyLessDifferentUsersAssignment_rejectedByTrigger() {
        User creator = userRepository.saveAndFlush(
                User.createCompanyOwner(
                        "boundary-individual-a@haja.test", "individual-a", "<password-hash-placeholder>"));
        User inspector = userRepository.saveAndFlush(
                User.createCompanyOwner(
                        "boundary-individual-b@haja.test", "individual-b", "<password-hash-placeholder>"));
        User facilityOwner = seedApprovedCompanyOwner(
                "boundary-facility-owner-a@haja.test", "boundary facility company A", "HA25-FACILITY-A");
        Long facilityId = seedFacility(facilityOwner.getCompanyId(), "무소속사용자간배정차단빌딩");

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(
                newInspection(facilityId, creator.getId(), inspector.getId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("effective membership");
    }

    @Test
    void bothCompanyLessSelfAssignment_rejectedByTrigger() {
        User individual = userRepository.saveAndFlush(
                User.createCompanyOwner("boundary-individual@haja.test", "individual", "<password-hash-placeholder>"));
        User facilityOwner = seedApprovedCompanyOwner(
                "boundary-facility-owner-b@haja.test", "boundary facility company B", "HA25-FACILITY-B");
        Long facilityId = seedFacility(facilityOwner.getCompanyId(), "개인빌딩");

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(
                newInspection(facilityId, individual.getId(), individual.getId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("effective membership");
    }

    @Test
    void staleCompanyPointerWithoutMembership_rejectedByTrigger() {
        User owner = seedApprovedCompanyOwner(
                "boundary-owner-d@haja.test", "boundary company D", "HA25-BOUNDARY-D");
        User inspector = userRepository.saveAndFlush(User.builder()
                .email("boundary-stale-d@haja.test")
                .name("stale inspector")
                .role(Role.INSPECTOR)
                .passwordHash("<password-hash-placeholder>")
                .status(UserStatus.ACTIVE)
                .build());
        inspector.assignToCompany(owner.getCompanyId());
        inspector = userRepository.saveAndFlush(inspector);
        Long inspectorId = inspector.getId();
        Long facilityId = seedFacility(owner.getCompanyId(), "stale 포인터 차단 빌딩");

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(
                newInspection(facilityId, owner.getId(), inspectorId)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("effective membership");
    }

    @Test
    void revokedMembershipWithRemainingPointer_rejectedByTrigger() {
        User owner = seedApprovedCompanyOwner(
                "boundary-owner-e@haja.test", "boundary company E", "HA25-BOUNDARY-E");
        User inspector = seedApprovedMember(
                owner.getCompanyId(), "boundary-revoked-e@haja.test", Role.INSPECTOR, UserStatus.ACTIVE);
        CompanyMembership membership = companyMembershipRepository
                .findByCompanyIdAndUserId(owner.getCompanyId(), inspector.getId()).orElseThrow();
        membership.revoke();
        companyMembershipRepository.saveAndFlush(membership);
        Long facilityId = seedFacility(owner.getCompanyId(), "회수 멤버십 차단 빌딩");

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(
                newInspection(facilityId, owner.getId(), inspector.getId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("effective membership");
    }

    @Test
    void activeMembershipButInvalidInspectorRole_rejectedByTrigger() {
        User owner = seedApprovedCompanyOwner(
                "boundary-owner-f@haja.test", "boundary company F", "HA25-BOUNDARY-F");
        User member = seedApprovedMember(
                owner.getCompanyId(), "boundary-user-f@haja.test", Role.USER, UserStatus.ACTIVE);
        Long facilityId = seedFacility(owner.getCompanyId(), "역할 차단 빌딩");

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(
                newInspection(facilityId, owner.getId(), member.getId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("effective membership");
    }

    @Test
    void suspendedInspectorWithActiveMembership_rejectedByTrigger() {
        User owner = seedApprovedCompanyOwner(
                "boundary-owner-g@haja.test", "boundary company G", "HA25-BOUNDARY-G");
        User inspector = seedApprovedMember(
                owner.getCompanyId(), "boundary-suspended-g@haja.test", Role.INSPECTOR, UserStatus.SUSPENDED);
        Long facilityId = seedFacility(owner.getCompanyId(), "정지 담당자 차단 빌딩");

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(
                newInspection(facilityId, owner.getId(), inspector.getId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("effective membership");
    }
}
