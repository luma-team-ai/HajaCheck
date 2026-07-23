package com.hajacheck.core.media.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.dto.MediaResponse;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

/**
 * MediaService.uploadMedia() 가 실 트랜잭션(Mockito 로는 검증 불가능한 트랜잭션 경계 버그 — 클래스 레벨
 * readOnly=true 가 새어들어가 MediaWriter.saveAll() 의 INSERT 가 읽기전용 트랜잭션 위반으로 실패하는 문제)
 * 없이 실제로 DB 에 커밋되는지 검증한다. CompanyAuthIntegrationTest 와 동일하게 실 PG(Testcontainers) +
 * 실 LocalFileStorage(임시경로)를 사용 — 클래스 레벨 @Transactional 을 붙이지 않아(각 서비스 호출이
 * 독립된 실 트랜잭션을 갖도록) 테스트 후 직접 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MediaServiceIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MediaService mediaService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private InspectionRepository inspectionRepository;
    @Autowired
    private MediaRepository mediaRepository;

    private Long ownerId;
    private Long inspectorId;
    private Long facilityId;
    private Long inspectionId;
    private Long companyId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(User.builder()
                .email("media-owner-" + System.nanoTime() + "@haja.com")
                .name("미디어테스트소유자")
                .role(Role.USER)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        String brn = "brn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)미디어테스트", brn, "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        // HAJA-25 배정 검증 트리거는 승인+검증된 회사만 허용하므로 사업자 검증·승인까지 진행한다.
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.save(company);
        owner.assignToCompany(company.getId());
        userRepository.save(owner);

        User inspector = userRepository.save(User.builder()
                .email("media-inspector-" + System.nanoTime() + "@haja.com")
                .name("미디어테스트점검자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .companyId(company.getId())
                .status(UserStatus.ACTIVE)
                .build());

        // created_by(owner)·assigned_inspector(inspector) 모두 회사의 유효한 APPROVED 멤버십을 가져야 한다.
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        CompanyMembership inspectorMembership =
                CompanyMembership.invite(company.getId(), inspector.getId(), owner.getId(), null);
        inspectorMembership.approve();
        companyMembershipRepository.save(inspectorMembership);

        Facility facility = facilityRepository.save(
                Facility.builder().companyId(company.getId()).name("미디어테스트시설").type("BUILDING").build());

        Inspection inspection = inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(inspector.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(InspectionStatus.CREATED)
                .build());

        this.ownerId = owner.getId();
        this.inspectorId = inspector.getId();
        this.facilityId = facility.getId();
        this.inspectionId = inspection.getId();
        this.companyId = company.getId();
    }

    @AfterEach
    void tearDown() {
        mediaRepository.findAll().stream()
                .filter(m -> m.getInspectionId().equals(inspectionId))
                .forEach(mediaRepository::delete);
        inspectionRepository.deleteById(inspectionId);
        facilityRepository.deleteById(facilityId);

        companyMembershipRepository.findAll().stream()
                .filter(m -> m.getCompanyId().equals(companyId))
                .forEach(companyMembershipRepository::delete);

        User owner = userRepository.findById(ownerId).orElseThrow();
        owner.assignToCompany(null);
        userRepository.save(owner);
        User inspector = userRepository.findById(inspectorId).orElseThrow();
        inspector.assignToCompany(null);
        userRepository.save(inspector);

        companyRepository.deleteById(companyId);
        userRepository.deleteById(inspectorId);
        userRepository.deleteById(ownerId);
    }

    private static byte[] realPngBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void uploadMedia_실트랜잭션으로DB커밋됨() throws IOException {
        MultipartFile file = new MockMultipartFile("files", "a.png", "image/png", realPngBytes());

        List<MediaResponse> result =
                mediaService.uploadMedia(inspectionId, ownerId, companyId, List.of(file));

        assertThat(result).hasSize(1);
        assertThat(mediaRepository.findById(result.get(0).id())).isPresent();
    }
}
