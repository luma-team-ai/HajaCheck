package com.hajacheck.core.media.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.hajacheck.support.PngTestFixtures;

/**
 * 미인증 요청이 SecurityConfig 의 anyRequest().authenticated() 로 실제 차단되어 401을
 * 반환하는지 고정하는 회귀 테스트(리뷰 P2). 두 엔드포인트 모두 @AuthenticationPrincipal
 * LoginUser 를 컨트롤러 진입 즉시 역참조하므로, 필터체인이 이 경로를 보호하지 못하게 되면
 * 401 대신 NPE(500)로 응답한다 — 이 테스트가 그 회귀를 잡는다.
 *
 * <p>클래스 레벨 @Transactional 을 붙이지 않는다: 썸네일 조회는 MediaService#getThumbnail 이
 * @Transactional(NOT_SUPPORTED) 로 트랜잭션 밖에서 실행되어(디스크 IO 중 DB 커넥션 미점유),
 * 롤백 트랜잭션 안에서 저장한 미커밋 픽스처를 보지 못한다. 따라서 MediaServiceIntegrationTest 와
 * 동일하게 실제로 커밋한 뒤 {@link #tearDown()} 에서 직접 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MediaControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
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
    @Autowired
    private FileStorageService fileStorage;

    @AfterEach
    void tearDown() {
        // 커밋된 픽스처를 FK 안전 순서로 정리한다: media → inspection → facility → membership →
        // (users.company_id 해제) → company → user. users.company_id↔companies.owner_user_id 순환 FK 때문에
        // 회사·사용자를 지우기 전에 company_id 를 먼저 끊는다.
        mediaRepository.deleteAll();
        inspectionRepository.deleteAll();
        facilityRepository.deleteAll();
        companyMembershipRepository.deleteAll();
        java.util.List<User> users = userRepository.findAll();
        users.forEach(u -> u.assignToCompany(null));
        userRepository.saveAll(users);
        companyRepository.deleteAll();
        userRepository.deleteAll();
    }

    // HAJA-25 배정 검증 트리거를 통과하는 담당자(=승인+검증 회사의 유효한 APPROVED INSPECTOR 멤버)를 시드한다.
    // owner 를 created_by·assigned_inspector 로 함께 재사용하므로 INSPECTOR 역할 + approvedOwner 멤버십을 준다.
    private User seedApprovedInspector(String email) {
        User owner = userRepository.save(User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "썸네일테스트회사", "REG-" + owner.getId(), "대표자",
                "서울시", null, "https://files.example/business.pdf", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.save(company);
        companyMembershipRepository.save(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        return userRepository.save(owner);
    }

    @Test
    void 업로드_미인증_401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "a.png", MediaType.IMAGE_PNG_VALUE, "PNGDATA".getBytes());

        mockMvc.perform(multipart("/api/inspections/{id}/media", 1L)
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 썸네일조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/media/{id}/thumbnail", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 상세이미지조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/media/{id}/detail", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 점검별미디어목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/inspections/{id}/media", 1L))
                .andExpect(status().isUnauthorized());
    }

    private static byte[] realPngBytes() throws java.io.IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * @RequestParam("files") 는 기본 required=true 라, multipart 요청에 "files" 파트 자체가 아예
     * 없으면 컨트롤러 진입 전에 Spring이 MissingServletRequestPartException을 던진다(리뷰 P2).
     * GlobalExceptionHandler.handleMissingPart가 이를 FILE_REQUIRED(400)로 매핑하는지, 실제
     * ApiResponse 스키마로 응답하는지 실제 컨테이너 요청으로 고정한다(서비스 계층의
     * files==null||isEmpty 분기와는 별개 경로 — 이쪽은 파트 자체가 없는 경우).
     */
    @Test
    void 업로드_files파트누락_400_FILE_REQUIRED() throws Exception {
        User owner = userRepository.save(User.builder()
                .email("no-files-owner@haja.com")
                .name("소유자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        LoginUser principal = new LoginUser(owner);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        mockMvc.perform(multipart("/api/inspections/{id}/media", 1L)
                        .with(csrf())
                        .with(authentication(auth)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FILE_REQUIRED"));
    }

    /**
     * 소유권 검증을 거쳐 사용자별로 다른 사적 이미지를 반환하는 엔드포인트라(리뷰 P2), 공유
     * 캐시/브라우저 캐시에 남으면 안 된다 — 응답에 Cache-Control: no-store, private가 실제로
     * 실려 나가는지 고정한다.
     */
    @Test
    void 썸네일조회_인증됨_CacheControl_noStore_private() throws Exception {
        User owner = seedApprovedInspector("thumb-owner@haja.com");
        Facility facility = facilityRepository.save(Facility.builder()
                .companyId(owner.getCompanyId())
                .name("테스트빌딩")
                .type("BUILDING")
                .build());
        Inspection inspection = inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(owner.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(InspectionStatus.CREATED)
                .build());
        FileStorageService.StoredFile thumb = fileStorage.storeBytes(
                PngTestFixtures.realJpeg(), "image/jpeg", "inspection-media-thumb",
                List.of("image/jpeg"), 1_000_000L);
        Media media = mediaRepository.save(Media.builder()
                .inspectionId(inspection.getId())
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl(thumb.storageKey())
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build());

        LoginUser principal = new LoginUser(owner);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        mockMvc.perform(get("/api/media/{id}/thumbnail", media.getId())
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")));
    }

    /**
     * 상세 이미지도 썸네일과 동일하게 사적 이미지라 공유 캐시 금지(no-store, private) — 원본에서
     * 실제로 디코딩·재인코딩까지 되는지(200 OK) 함께 고정한다(#788).
     */
    @Test
    void 상세이미지조회_인증됨_CacheControl_noStore_private() throws Exception {
        User owner = seedApprovedInspector("detail-owner@haja.com");
        Facility facility = facilityRepository.save(Facility.builder()
                .companyId(owner.getCompanyId())
                .name("테스트빌딩")
                .type("BUILDING")
                .build());
        Inspection inspection = inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(owner.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(InspectionStatus.CREATED)
                .build());
        FileStorageService.StoredFile original = fileStorage.storeBytes(
                realPngBytes(), "image/png", "inspection-media",
                List.of("image/png"), 1_000_000L);
        Media media = mediaRepository.save(Media.builder()
                .inspectionId(inspection.getId())
                .fileType(MediaFileType.IMAGE)
                .originalUrl(original.storageKey())
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build());

        LoginUser principal = new LoginUser(owner);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        mockMvc.perform(get("/api/media/{id}/detail", media.getId())
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")));
    }

    /**
     * 점검 회차별 미디어 목록 조회(#803 분석 결과 뷰어) — 업로드된 모든 미디어를 반환한다(하자 유무 무관).
     * 각 항목에 thumbnailUrl, detailUrl이 포함되어야 한다.
     */
    @Test
    void 점검별미디어목록조회_정상_200() throws Exception {
        User owner = seedApprovedInspector("media-list-owner@haja.com");
        Facility facility = facilityRepository.save(Facility.builder()
                .companyId(owner.getCompanyId())
                .name("테스트빌딩")
                .type("BUILDING")
                .build());
        Inspection inspection = inspectionRepository.save(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(owner.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(InspectionStatus.CREATED)
                .build());

        // 2개의 미디어 저장 (detailUrl 있음)
        FileStorageService.StoredFile thumb1 = fileStorage.storeBytes(
                PngTestFixtures.realJpeg(), "image/jpeg", "inspection-media-thumb",
                List.of("image/jpeg"), 1_000_000L);
        FileStorageService.StoredFile detail1 = fileStorage.storeBytes(
                PngTestFixtures.realJpeg(), "image/jpeg", "inspection-media-detail",
                List.of("image/jpeg"), 8_000_000L);
        Media media1 = mediaRepository.save(Media.builder()
                .inspectionId(inspection.getId())
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/x1.png")
                .thumbnailUrl(thumb1.storageKey())
                .detailUrl(detail1.storageKey())
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .capturedAt(LocalDateTime.now())
                .build());

        FileStorageService.StoredFile thumb2 = fileStorage.storeBytes(
                PngTestFixtures.realJpeg(), "image/jpeg", "inspection-media-thumb",
                List.of("image/jpeg"), 1_000_000L);
        FileStorageService.StoredFile detail2 = fileStorage.storeBytes(
                PngTestFixtures.realJpeg(), "image/jpeg", "inspection-media-detail",
                List.of("image/jpeg"), 8_000_000L);
        Media media2 = mediaRepository.save(Media.builder()
                .inspectionId(inspection.getId())
                .fileType(MediaFileType.IMAGE)
                .originalUrl("inspection-media/x2.png")
                .thumbnailUrl(thumb2.storageKey())
                .detailUrl(detail2.storageKey())
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .capturedAt(LocalDateTime.now())
                .build());

        LoginUser principal = new LoginUser(owner);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        mockMvc.perform(get("/api/inspections/{id}/media", inspection.getId())
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(media1.getId()))
                .andExpect(jsonPath("$.data[0].thumbnailUrl").value("/api/media/" + media1.getId() + "/thumbnail"))
                .andExpect(jsonPath("$.data[0].detailUrl").value("/api/media/" + media1.getId() + "/detail"))
                .andExpect(jsonPath("$.data[1].id").value(media2.getId()))
                .andExpect(jsonPath("$.data[1].thumbnailUrl").value("/api/media/" + media2.getId() + "/thumbnail"))
                .andExpect(jsonPath("$.data[1].detailUrl").value("/api/media/" + media2.getId() + "/detail"));
    }

    /**
     * IDOR 차단: 타인 회사의 점검 회차로 미디어 목록을 조회하려 하면 404(INSPECTION_NOT_FOUND)를 반환한다.
     * 미존재와 타인 소유를 구분하지 않고 동일하게 404로 응답한다.
     */
    @Test
    void 점검별미디어목록조회_타인회차_404() throws Exception {
        User owner1 = seedApprovedInspector("media-list-owner1@haja.com");
        User owner2 = seedApprovedInspector("media-list-owner2@haja.com");

        // owner1의 회차 생성
        Facility facility1 = facilityRepository.save(Facility.builder()
                .companyId(owner1.getCompanyId())
                .name("테스트빌딩1")
                .type("BUILDING")
                .build());
        Inspection inspection1 = inspectionRepository.save(Inspection.builder()
                .facilityId(facility1.getId())
                .createdBy(owner1.getId())
                .assignedInspectorId(owner1.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(InspectionStatus.CREATED)
                .build());

        // owner2가 owner1의 회차 접근 시도
        LoginUser principal2 = new LoginUser(owner2);
        UsernamePasswordAuthenticationToken auth2 =
                new UsernamePasswordAuthenticationToken(principal2, null, principal2.getAuthorities());

        mockMvc.perform(get("/api/inspections/{id}/media", inspection1.getId())
                        .with(authentication(auth2)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INSPECTION_NOT_FOUND"));
    }
}
