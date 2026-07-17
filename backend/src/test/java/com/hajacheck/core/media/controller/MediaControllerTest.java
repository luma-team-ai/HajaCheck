package com.hajacheck.core.media.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미인증 요청이 SecurityConfig 의 anyRequest().authenticated() 로 실제 차단되어 401을
 * 반환하는지 고정하는 회귀 테스트(리뷰 P2). 두 엔드포인트 모두 @AuthenticationPrincipal
 * LoginUser 를 컨트롤러 진입 즉시 역참조하므로, 필터체인이 이 경로를 보호하지 못하게 되면
 * 401 대신 NPE(500)로 응답한다 — 이 테스트가 그 회귀를 잡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MediaControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private InspectionRepository inspectionRepository;
    @Autowired
    private MediaRepository mediaRepository;
    @Autowired
    private FileStorageService fileStorage;

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
        User owner = userRepository.save(User.builder()
                .email("thumb-owner@haja.com")
                .name("소유자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        Facility facility = facilityRepository.save(Facility.builder()
                .ownerId(owner.getId())
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
                "THUMBDATA".getBytes(), "image/jpeg", "inspection-media-thumb",
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
}
