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
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.config.MediaUploadProperties;
import com.hajacheck.core.media.dto.MediaResponse;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaPurpose;
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
import org.springframework.test.util.ReflectionTestUtils;
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
    private FacilityService facilityService;
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
        when(properties.getMaxFilesPerRequest()).thenReturn(50);
        when(properties.getAllowedContentTypes()).thenReturn(List.of("image/jpeg", "image/png"));
        when(properties.getMaxSizeBytes()).thenReturn(20_000_000L);
        when(properties.getThumbnailMaxDimension()).thenReturn(400);
    }

    private static byte[] realPngBytes() throws IOException {
        return realPngBytes(4, 4);
    }

    private static byte[] realPngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
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
        when(fileStorage.storeBytes(any(), eq("image/jpeg"), eq("inspection-media-detail"), any(), anyLong()))
                .thenReturn(new StoredFile("/files/inspection-media-detail/x.jpg", "inspection-media-detail/x.jpg"));
        when(properties.getDetailMaxDimension()).thenReturn(1600);
    }

    @Test
    void uploadMedia_정상_다중파일저장_썸네일생성_EXIF조립() throws IOException {
        byte[] png = realPngBytes();
        MultipartFile file1 = pngFile("a.png", png);
        MultipartFile file2 = pngFile("b.png", png);
        stubStorage();
        when(mediaWriter.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<MediaResponse> result = service.uploadMedia(1L, 200L, 100L, List.of(file1, file2), MediaPurpose.INSPECTION_SOURCE);

        assertThat(result).hasSize(2);
        verify(inspectionService).getInspection(200L, 100L, 1L);
        verify(fileStorage, times(2)).store(any(), eq("inspection-media"), any(), anyLong());
        verify(fileStorage, times(2))
                .storeBytes(any(), eq("image/jpeg"), eq("inspection-media-thumb"), any(), anyLong());
        verify(fileStorage, times(2))
                .storeBytes(any(), eq("image/jpeg"), eq("inspection-media-detail"), any(), anyLong());
        verify(fileStorage, never()).delete(anyString());

        ArgumentCaptor<List<Media>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaWriter).saveAll(captor.capture());
        Media saved = captor.getValue().get(0);
        assertThat(saved.getInspectionId()).isEqualTo(1L);
        assertThat(saved.isMimeSignatureVerified()).isTrue();
        assertThat(saved.getMimeType()).isEqualTo("image/png");
        assertThat(saved.getOriginalUrl()).isEqualTo("inspection-media/x.png");
        assertThat(saved.getThumbnailUrl()).isEqualTo("inspection-media-thumb/x.jpg");
        assertThat(saved.getDetailUrl()).isEqualTo("inspection-media-detail/x.jpg");
        assertThat(saved.getOriginalFilename()).isEqualTo("a.png");
        assertThat(saved.getPurpose()).isEqualTo(MediaPurpose.INSPECTION_SOURCE);
    }

    @Test
    void uploadMedia_purpose_DEFECT_ACTION_지정시_저장된_미디어에_그대로_반영된다() throws IOException {
        // #1641 — 조치 후 사진 업로드 경로(defectMediaApi.uploadActionPhoto)가 DEFECT_ACTION을
        // 명시하면 저장되는 Media 로우에 그대로 반영돼야 분석결과뷰어/AI 재분석 필터가 걸러낼 수 있다.
        byte[] png = realPngBytes();
        stubStorage();
        when(mediaWriter.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.uploadMedia(1L, 200L, 100L, List.of(pngFile("action.png", png)), MediaPurpose.DEFECT_ACTION);

        ArgumentCaptor<List<Media>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaWriter).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getPurpose()).isEqualTo(MediaPurpose.DEFECT_ACTION);
    }

    @Test
    void uploadMedia_purpose_null이면_INSPECTION_SOURCE로_기본처리된다() throws IOException {
        // 컨트롤러가 @RequestParam defaultValue로 이미 채워 넘기지만, 서비스 레벨 null-세이프 기본값
        // 자체도 고정한다(호출부가 컨트롤러 하나만이 아닐 가능성 방어).
        byte[] png = realPngBytes();
        stubStorage();
        when(mediaWriter.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.uploadMedia(1L, 200L, 100L, List.of(pngFile("null-purpose.png", png)), null);

        ArgumentCaptor<List<Media>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaWriter).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getPurpose()).isEqualTo(MediaPurpose.INSPECTION_SOURCE);
    }

    @Test
    void uploadMedia_255자_초과_파일명은_잘라서_저장하되_서로게이트_페어_경계를_보존한다() throws IOException {
        // PR 리뷰 P3 — 절단 경계(255번째 코드유닛)가 서로게이트 페어(이모지 등 BMP 밖 문자) 중간이면
        // 안 잘리고 페어 전체가 빠져야 한다(반쪽만 남아 깨진 문자(U+FFFD)로 표시되면 안 됨).
        String surrogatePair = "😀"; // 😀, high+low surrogate 2코드유닛
        String longName = "a".repeat(254) + surrogatePair + ".png"; // 254 + 2(서로게이트) + 4(".png") = 260자
        byte[] png = realPngBytes();
        stubStorage();
        when(mediaWriter.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.uploadMedia(1L, 200L, 100L, List.of(pngFile(longName, png)), MediaPurpose.INSPECTION_SOURCE);

        ArgumentCaptor<List<Media>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaWriter).saveAll(captor.capture());
        String saved = captor.getValue().get(0).getOriginalFilename();
        assertThat(saved).hasSize(254); // 서로게이트 페어를 통째로 버려 254자리에서 끊긴다(255자가 아님).
        assertThat(saved.charAt(saved.length() - 1)).isEqualTo('a');
        assertThat(Character.isSurrogate(saved.charAt(saved.length() - 1))).isFalse();
    }

    @Test
    void uploadMedia_빈_파일명은_null로_정규화해_저장한다() throws IOException {
        // PR머신 P3 — MultipartFile#getOriginalFilename()은 계약상 ""를 반환할 수 있다. ""가 그대로
        // 저장되면 표시 단계의 "이미지 N" 폴백(null 검사)이 발동하지 않아 파일명 셀이 빈칸이 된다.
        byte[] png = realPngBytes();
        stubStorage();
        when(mediaWriter.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.uploadMedia(1L, 200L, 100L, List.of(pngFile("   ", png)), MediaPurpose.INSPECTION_SOURCE);

        ArgumentCaptor<List<Media>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaWriter).saveAll(captor.capture());
        assertThat(captor.getValue().get(0).getOriginalFilename()).isNull();
    }

    @Test
    void uploadMedia_빈목록_FILE_REQUIRED_아무것도호출안함() {
        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 100L, List.of(), MediaPurpose.INSPECTION_SOURCE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_REQUIRED));

        verify(inspectionService, never()).getInspection(anyLong(), anyLong(), anyLong());
        verify(mediaWriter, never()).saveAll(anyList());
    }

    @Test
    void uploadMedia_상한이내_다건저장() throws IOException {
        byte[] png = realPngBytes();
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            files.add(pngFile("a" + i + ".png", png));
        }
        stubStorage();
        when(mediaWriter.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<MediaResponse> result = service.uploadMedia(1L, 200L, 100L, files, MediaPurpose.INSPECTION_SOURCE);

        assertThat(result).hasSize(21);
        verify(inspectionService).getInspection(200L, 100L, 1L);
    }

    @Test
    void uploadMedia_개수초과_MEDIA_COUNT_EXCEEDED_소유권검증전에거부() throws IOException {
        byte[] png = realPngBytes();
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            files.add(pngFile("a" + i + ".png", png));
        }

        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 100L, files, MediaPurpose.INSPECTION_SOURCE))
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

        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 999L, List.of(file), MediaPurpose.INSPECTION_SOURCE))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));

        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
    }

    @Test
    void uploadMedia_매직바이트불일치_FILE_INVALID_TYPE_아무것도저장안함() {
        // content-type 은 image/jpeg 라고 선언했지만 실제 바이트는 JPEG 시그니처가 아니다.
        MultipartFile fakeJpeg = new MockMultipartFile("files", "fake.jpg", "image/jpeg", "not-a-real-jpeg".getBytes());

        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 100L, List.of(fakeJpeg), MediaPurpose.INSPECTION_SOURCE))
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

        assertThatThrownBy(() -> service.uploadMedia(1L, 200L, 100L, List.of(file1, file2), MediaPurpose.INSPECTION_SOURCE))
                .isInstanceOf(RuntimeException.class);

        // 파일 2개 × (원본 + 썸네일 + 상세이미지) = 6건 보상삭제.
        verify(fileStorage, times(2)).delete("inspection-media/x.png");
        verify(fileStorage, times(2)).delete("inspection-media-thumb/x.jpg");
        verify(fileStorage, times(2)).delete("inspection-media-detail/x.jpg");
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
    void getDetailImage_존재하지않는미디어_MEDIA_NOT_FOUND() {
        when(mediaRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getDetailImage(200L, 100L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
    }

    @Test
    void getDetailImage_타인소유_존재하지않는id와동일하게MEDIA_NOT_FOUND() {
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

        assertThatThrownBy(() -> service.getDetailImage(200L, 999L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
    }

    @Test
    void getDetailImage_저장된상세이미지파일유실_MEDIA_NOT_FOUND() {
        // V13 이후 정상 업로드 행(detailUrl 존재) — 저장된 상세이미지 파일 자체가 디스크에서 유실된 케이스.
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .detailUrl("inspection-media-detail/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        when(fileStorage.read("inspection-media-detail/x.jpg"))
                .thenThrow(new BusinessException(ErrorCode.FILE_NOT_FOUND));

        assertThatThrownBy(() -> service.getDetailImage(200L, 100L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
    }

    @Test
    void getDetailImage_본인소유_저장된상세이미지그대로반환_재인코딩안함() {
        // getThumbnail()과 동일 패턴 — 업로드 시 저장해 둔 파일을 읽기만 한다(PR머신 리뷰 P2: 조회마다
        // 재인코딩하던 성능 문제 해소 확인). detailMaxDimension을 다시 계산하지 않으므로 호출도 없어야 한다.
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .detailUrl("inspection-media-detail/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        when(fileStorage.read("inspection-media-detail/x.jpg")).thenReturn(new byte[] {9, 9, 9});

        MediaService.ThumbnailFile detail = service.getDetailImage(200L, 100L, 10L);

        assertThat(detail.mimeType()).isEqualTo("image/jpeg");
        assertThat(detail.content()).containsExactly(9, 9, 9);
        verify(properties, never()).getDetailMaxDimension();
    }

    @Test
    void getDetailImage_레거시행_detailUrl없음_원본에서즉석생성하며detailMaxDimension실제적용() throws IOException {
        // V13 이전 업로드된 기존 행(detailUrl 미설정) 전용 폴백 경로. PR머신 리뷰 P2: "성공적으로
        // 재인코딩됨"만 확인하던 이전 테스트는 detailMaxDimension이 실제로 쓰였는지(썸네일 크기로
        // 잘못 축소되지 않는지) 고정하지 못했다 — 원본보다 작고 thumbnailMaxDimension(400)보다 크고
        // detailMaxDimension(1600)보다도 큰 2000x1500 이미지를 넣어, 결과가 정확히 1600으로
        // 축소됐는지(=detailMaxDimension 사용, thumbnailMaxDimension 오사용이면 400이 됐을 것) 픽셀
        // 단위로 직접 검증한다.
        when(properties.getDetailMaxDimension()).thenReturn(1600);
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build(); // detailUrl 미지정 → null
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        when(fileStorage.read("inspection-media/x.png")).thenReturn(realPngBytes(2000, 1500));

        MediaService.ThumbnailFile detail = service.getDetailImage(200L, 100L, 10L);

        assertThat(detail.mimeType()).isEqualTo("image/jpeg");
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(detail.content()));
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(1600);
        verify(properties).getDetailMaxDimension();
        verify(properties, never()).getThumbnailMaxDimension();
    }

    @Test
    void getDetailImage_레거시행_동시요청이_동시생성상한을넘지않는다() throws Exception {
        // PR머신 리뷰 P2 — 캐시 없이 레거시 행을 즉석 생성하므로, 배포 직후 레거시 인스펙션을 열면
        // 그리드 하자 수만큼 원본 디코딩이 한꺼번에 몰릴 수 있다. MediaService의
        // MAX_CONCURRENT_LEGACY_DETAIL_GENERATION(4) 세마포어가 실제로 동시 실행을 제한하는지,
        // fileStorage.read 호출 중 동시 진행 수를 직접 세어 검증한다(상한보다 많은 10개를 동시 발사).
        when(properties.getDetailMaxDimension()).thenReturn(1600);
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        byte[] original = realPngBytes(800, 600);
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger(0);
        when(fileStorage.read("inspection-media/x.png")).thenAnswer(invocation -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.updateAndGet(prev -> Math.max(prev, current));
            Thread.sleep(50);
            inFlight.decrementAndGet();
            return original;
        });

        int totalRequests = 10;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(totalRequests);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < totalRequests; i++) {
                futures.add(pool.submit(() -> service.getDetailImage(200L, 100L, 10L)));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(maxInFlight.get()).isLessThanOrEqualTo(4);
    }

    @Test
    void getDetailImage_레거시행_대기상한초과시_블로킹대신BUSY로즉시실패한다() throws Exception {
        // PR머신 리뷰 P1 — 세마포어 permit이 없을 때 acquire()로 무기한 대기하면 요청 스레드(Tomcat
        // 워커)가 계속 점유돼 전역 가용성 표면이 된다. tryAcquire(timeout) 초과 시 블로킹 대신
        // MEDIA_DETAIL_GENERATION_BUSY(503)로 즉시 실패하는지 고정한다. 실제 대기 없이 분기만 검증하도록
        // 대기 상한을 0으로 줄인 뒤(tryAcquire(0, ...) = permit 없으면 즉시 실패), permit 4개를 모두
        // 다른 스레드가 붙잡고 있는 상태를 만든다.
        ReflectionTestUtils.setField(service, "legacyDetailGenerationWaitSeconds", 0L);
        when(properties.getDetailMaxDimension()).thenReturn(1600);
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        java.util.concurrent.CountDownLatch releaseLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch permitsHeldLatch = new java.util.concurrent.CountDownLatch(4);
        when(fileStorage.read("inspection-media/x.png")).thenAnswer(invocation -> {
            permitsHeldLatch.countDown();
            releaseLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return realPngBytes(800, 600);
        });

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        try {
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> service.getDetailImage(200L, 100L, 10L));
            }
            assertThat(permitsHeldLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.getDetailImage(200L, 100L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                            .isEqualTo(ErrorCode.MEDIA_DETAIL_GENERATION_BUSY));
        } finally {
            releaseLatch.countDown();
            pool.shutdown();
        }
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

    // ── 시설물 대표 사진(#632/#652, HAJA-377) ──────────────────────────────────────────────

    @Test
    void loadOwnedMedia_facility전용로우_본인회사_FacilityService로인가_썸네일반환() {
        // facility_id 만 채워진 로우(inspection_id=null) — loadOwnedMedia 가 InspectionService 가 아니라
        // FacilityService.get() 으로 인가 분기해야 한다(리스크 감사 필수처리 1). inspection 경로로 갔다면
        // media.getInspectionId()=null 로 500/인가 누락이 났을 것이다.
        Media media = Media.builder()
                .facilityId(7L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        when(fileStorage.read("inspection-media-thumb/x.jpg")).thenReturn(new byte[] {4, 5, 6});

        MediaService.ThumbnailFile thumbnail = service.getThumbnail(200L, 100L, 10L);

        assertThat(thumbnail.content()).containsExactly(4, 5, 6);
        verify(facilityService).get(200L, 100L, 7L);
        verify(inspectionService, never()).getInspection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void loadOwnedMedia_facility전용로우_타사접근_존재하지않는id와동일하게MEDIA_NOT_FOUND() {
        // 타사 시설물 사진 조회 — FacilityService.get() 이 FACILITY_NOT_FOUND 를 던지고, loadOwnedMedia 는
        // 이를 MEDIA_NOT_FOUND(404)로 통일해 존재 여부 열거(cross-company IDOR)를 막아야 한다.
        Media media = Media.builder()
                .facilityId(7L)
                .fileType(com.hajacheck.core.media.entity.MediaFileType.IMAGE)
                .originalUrl("inspection-media/x.png")
                .thumbnailUrl("inspection-media-thumb/x.jpg")
                .mimeSignatureVerified(true)
                .mimeType("image/png")
                .build();
        when(mediaRepository.findById(10L)).thenReturn(java.util.Optional.of(media));
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(facilityService).get(200L, 999L, 7L);

        assertThatThrownBy(() -> service.getThumbnail(200L, 999L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
        verify(inspectionService, never()).getInspection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void uploadFacilityPhotos_4장_정상저장_facility_id로분기() throws IOException {
        byte[] png = realPngBytes();
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            files.add(pngFile("f" + i + ".png", png));
        }
        stubStorage();
        when(mediaRepository.countByFacilityId(7L)).thenReturn(0L);
        when(mediaWriter.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<MediaResponse> result = service.uploadFacilityPhotos(7L, 200L, 100L, files);

        assertThat(result).hasSize(4);
        verify(facilityService).get(200L, 100L, 7L);
        verify(inspectionService, never()).getInspection(anyLong(), anyLong(), anyLong());

        ArgumentCaptor<List<Media>> captor = ArgumentCaptor.forClass(List.class);
        verify(mediaWriter).saveAll(captor.capture());
        Media saved = captor.getValue().get(0);
        assertThat(saved.getFacilityId()).isEqualTo(7L);
        assertThat(saved.getInspectionId()).isNull();
        // 시설물 대표 사진은 조치 후 사진 개념이 없다 — 항상 INSPECTION_SOURCE로 저장(#1641).
        assertThat(saved.getPurpose()).isEqualTo(MediaPurpose.INSPECTION_SOURCE);
    }

    @Test
    void uploadFacilityPhotos_기존4장에추가업로드_5번째거부_파일저장안함() throws IOException {
        byte[] png = realPngBytes();
        MultipartFile file = pngFile("f.png", png);
        // 이미 4장 보유 — 1장 더 올리면 4+1>4 로 상한 초과.
        when(mediaRepository.countByFacilityId(7L)).thenReturn(4L);

        assertThatThrownBy(() -> service.uploadFacilityPhotos(7L, 200L, 100L, List.of(file)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_PHOTO_COUNT_EXCEEDED));

        // 상한 검증은 파일 저장 전에 이뤄져야 한다 — 아무 파일도 저장/보상삭제되지 않는다.
        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
        verify(mediaWriter, never()).saveAll(anyList());
    }

    @Test
    void uploadFacilityPhotos_타사시설물_예외전파_카운트조회나저장안함() throws IOException {
        byte[] png = realPngBytes();
        MultipartFile file = pngFile("f.png", png);
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(facilityService).get(200L, 999L, 7L);

        assertThatThrownBy(() -> service.uploadFacilityPhotos(7L, 200L, 999L, List.of(file)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));

        verify(mediaRepository, never()).countByFacilityId(anyLong());
        verify(fileStorage, never()).store(any(), anyString(), any(), anyLong());
    }

    @Test
    void uploadFacilityPhotos_빈목록_FILE_REQUIRED_소유권검증전거부() {
        assertThatThrownBy(() -> service.uploadFacilityPhotos(7L, 200L, 100L, List.of()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_REQUIRED));

        verify(facilityService, never()).get(anyLong(), anyLong(), anyLong());
        verify(mediaWriter, never()).saveAll(anyList());
    }
}
