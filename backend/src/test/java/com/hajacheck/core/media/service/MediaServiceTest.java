package com.hajacheck.core.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.config.MediaUploadProperties;
import com.hajacheck.core.media.dto.MediaResponse;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * ImageSignatureValidator/ImageThumbnailGenerator 는 static 유틸이라 목킹하지 않고 실제 이미지 바이트로
 * 검증한다(더 사실적이고, 별도 static mock 인프라가 필요 없음).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MediaServiceTest {

    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private MediaWriter mediaWriter;
    @Mock
    private InspectionService inspectionService;
    @Mock
    private FileStorageService fileStorage;
    @Mock
    private MediaUploadProperties properties;
    @Mock
    private CompanyScopeGuard companyScopeGuard;

    @InjectMocks
    private MediaService service;

    @BeforeEach
    void setUp() {
        when(properties.getMaxFilesPerRequest()).thenReturn(20);
        when(properties.getAllowedContentTypes()).thenReturn(List.of("image/jpeg", "image/png"));
        when(properties.getMaxSizeBytes()).thenReturn(20_000_000L);
        when(properties.getThumbnailMaxDimension()).thenReturn(400);
    }

    private static byte[] realPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile pngFile(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "image/png", bytes);
    }

    private void stubStorage() {
        when(fileStorage.store(any(), eq("inspection-media"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/inspection-media/x.png", "inspection-media/x.png"));
        when(fileStorage.storeBytes(any(), eq("image/jpeg"), eq("inspection-media-thumb"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/inspection-media-thumb/x.jpg", "inspection-media-thumb/x.jpg"));
    }

    @Test
    void uploadMedia_정상_다중파일저장_썸네일생성_EXIF조립() throws IOException {
        byte[] png = realPngBytes();
        MultipartFile file1 = pngFile("a.png", png);
        MultipartFile file2 = pngFile("b.png", png);
        stubStorage();
        when(mediaWriter.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<MediaResponse> result = service.uploadMedia(1L, 200L, 100L, List.of(file1, file2));

        assertThat(result).hasSize(2);
        verify(inspectionService).getInspection(200L, 100L, 1L);
        verify(fileStorage, times(2)).store(any(), eq("inspection-media"), any(), anyLong());
        verify(fileStorage, times(2))
                .storeBytes(any(), eq("image/jpeg"), eq("inspection-media-thumb"), any(), anyLong());
        verify(fileStorage, never()).delete(anyString());

        ArgumentCaptor<List<Media>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaWriter).saveAll(captor.capture());
        Media saved = captor.getValue().get(0);
        assertThat(saved.getInspectionId()).isEqualTo(1L);
        assertThat(saved.isMimeSignatureVerified()).isTrue();
        assertThat(saved.getMimeType()).isEqualTo("image/png");
        assertThat(saved.getOriginalUrl()).isEqualTo("inspection-media/x.png");
        assertThat(saved.getThumbnailUrl()).isEqualTo("inspection-media-thumb/x.jpg");
    }

    @Test
    void uploadMedia_빈목록_FILE_REQUIRED_아무것도호출안함() {
        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 100L, List.of()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_REQUIRED));

        verify(inspectionService, never()).getInspection(anyLong(), anyLong(), anyLong());
        verify(mediaWriter, never()).saveAll(anyList());
    }

    @Test
    void uploadMedia_개수초과_MEDIA_COUNT_EXCEEDED_소유권검증전에거부() throws IOException {
        byte[] png = realPngBytes();
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            files.add(pngFile("a" + i + ".png", png));
        }

        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 100L, files))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_COUNT_EXCEEDED));

        verify(inspectionService, never()).getInspection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void uploadMedia_타인소유점검_예외전파_저장호출안함() throws IOException {
        byte[] png = realPngBytes();
        MultipartFile file = pngFile("a.png", png);
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(200L, 999L, 1L);

        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 999L, List.of(file)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
    }

    @Test
    void uploadMedia_매직바이트불일치_FILE_INVALID_TYPE_아무것도저장안함() {
        // content-type 은 image/jpeg 라고 선언했지만 실제 바이트는 JPEG 시그니처가 아니다.
        MultipartFile fakeJpeg = new MockMultipartFile("files", "fake.jpg", "image/jpeg", "not-a-real-jpeg".getBytes());

        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 100L, List.of(fakeJpeg)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_INVALID_TYPE));

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
        verify(mediaWriter, never()).saveAll(anyList());
    }

    @Test
    void uploadMedia_DB저장실패_저장한원본과썸네일전부보상삭제() throws IOException {
        byte[] png = realPngBytes();
        MultipartFile file1 = pngFile("a.png", png);
        MultipartFile file2 = pngFile("b.png", png);
        stubStorage();
        when(mediaWriter.saveAll(anyList())).thenThrow(new RuntimeException("DB 저장 실패"));

        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 100L, List.of(file1, file2)))
                .isInstanceOf(RuntimeException.class);

        // 파일 2개 × (원본 + 썸네일) = 4건 보상삭제.
        verify(fileStorage, times(2)).delete("inspection-media/x.png");
        verify(fileStorage, times(2)).delete("inspection-media-thumb/x.jpg");
    }

    @Test
    void getThumbnail_존재하지않는미디어_MEDIA_NOT_FOUND() {
        when(mediaRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getThumbnail(200L, 100L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
    }

    @Test
    void getThumbnail_타인소유_존재하지않는id와동일하게MEDIA_NOT_FOUND() {
        // 리뷰 P2: 타인 소유(FACILITY_NOT_FOUND)와 아예 없는 id(MEDIA_NOT_FOUND)의 error.code가
        // 다르면 공격자가 이를 존재 열거에 악용할 수 있다 — 두 경우 모두 동일한 MEDIA_NOT_FOUND(404)로
        // 응답해 "이 미디어가 존재하는지"를 외부에서 구분할 수 없어야 한다.
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(200L, 999L, 1L);

        assertThatThrownBy(() -> service.getThumbnail(200L, 999L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
    }

    @Test
    void getThumbnail_썸네일URL은있으나디스크파일유실_MEDIA_NOT_FOUND() {
        // 리뷰 P2: DB 행은 존재하나 디스크 파일이 유실된 경우(보상삭제 경합 등) — 저장소는
        // FILE_NOT_FOUND(404)를 던지고, getThumbnail()은 이를 다른 두 "없음" 케이스와 통일해
        // MEDIA_NOT_FOUND로 재매핑해야 한다(구현 세부인 FILE_NOT_FOUND를 API에 그대로 노출하지 않음).
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        when(fileStorage.read("inspection-media-thumb/x.jpg"))
                .thenThrow(new BusinessException(ErrorCode.FILE_NOT_FOUND));

        assertThatThrownBy(() -> service.getThumbnail(200L, 100L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
    }

    @Test
    void getThumbnail_본인소유_썸네일바이트반환() {
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        when(fileStorage.read("inspection-media-thumb/x.jpg")).thenReturn(new byte[] {1, 2, 3});

        MediaService.ThumbnailFile thumbnail = service.getThumbnail(200L, 100L, 10L);

        assertThat(thumbnail.mimeType()).isEqualTo("image/jpeg");
        assertThat(thumbnail.content()).containsExactly(1, 2, 3);
    }
    @Test
    void getThumbnail_무소속사용자_FORBIDDEN을404로변환하지않는다() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(200L, null);

        assertThatThrownBy(() -> service.getThumbnail(200L, null, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(mediaRepository, never()).findById(anyLong());
    }

    @Test
    void getThumbnail_검증중FORBIDDEN도404로변환하지않는다() {
        Media media = Media.builder().inspectionId(1L).thumbnailUrl("thumb/x.jpg").build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(inspectionService).getInspection(200L, 100L, 1L);

        assertThatThrownBy(() -> service.getThumbnail(200L, 100L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
