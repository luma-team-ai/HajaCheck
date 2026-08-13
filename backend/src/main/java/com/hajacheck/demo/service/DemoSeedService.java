package com.hajacheck.demo.service;

import com.hajacheck.auth.config.DemoProperties;
import com.hajacheck.auth.config.PolicyProperties;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyAccountWriter;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.auth.support.FileStorageService.StoredFile;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.core.media.support.ImageThumbnailGenerator;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.repository.ReportRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 데모 회사·계정·콘텐츠 시드(#1626) — {@code DemoDataSeeder}(기동 옵트인)와
 * {@code DemoResetService}(리셋 후 복원)가 공유하는 단일 시드 경로다. "삭제와 시드가 코드 경로를
 * 공유"해야 리셋 복원 상태와 최초 시드 상태가 갈라지지 않는다(handoff 설계 요구).
 *
 * <p><b>계정·회사 생성은 정식 가입 경로를 그대로 재사용한다</b> — {@link CompanyAccountWriter#createAccount}
 * (User→Company→VERIFIED/APPROVED→멤버십→동의이력→FREE 플랜, 한 트랜잭션). OCR·국세청 진위확인만
 * 건너뛴다(데모 회사는 실사업자가 아니다 — ocr_raw 에 {@code source=DEMO_SEED} provenance 를 남긴다).
 *
 * <p><b>플랜은 FREE</b> — 기존 3종(FREE/STANDARD/ENTERPRISE) 중 상담 미포함(hasCounselorAccess=false)
 * 플랜은 FREE 뿐이다(V2 시드). 상담 대기열 오염 차단이 우선 요구라 FREE 를 쓴다. ⚠️ 그 대가로 FREE
 * 쿼터(시설물 1·좌석 1)가 실측 기준({@code QuotaService#measureFacilities})으로 걸려, 시드된 시설물이
 * 이미 한도를 넘기 때문에 <b>방문자의 신규 시설물 등록·구성원 추가는 403 으로 막힌다</b>(기존 데이터
 * 조회·검수·조치·보고서 흐름은 쿼터와 무관). 이 트레이드오프는 이슈 #1626 리뷰에서 확정할 것.
 *
 * <p><b>미디어는 정식 저장 계층({@link FileStorageService})을 그대로 쓴다</b> — 원본·썸네일(400px)·
 * 상세(1600px) 3종을 {@code MediaService.storeAndBuild} 와 동일한 카테고리·형식으로 저장하므로
 * 썸네일/상세 서빙 엔드포인트가 시드 미디어에도 그대로 동작한다. 청크 업로드·매직바이트 검증
 * ({@code ImageSignatureValidator})은 <b>클라이언트 업로드 입력</b>에 대한 방어라 서버 리소스 번들
 * 이미지에는 적용 대상이 아니다(검증기가 MultipartFile 전제이기도 하다). 대신
 * {@link ImageThumbnailGenerator}가 디코딩에 성공해야 저장이 진행되므로 손상 파일은 여기서 걸러진다 —
 * 우회가 아니라 신뢰 경계가 다른 것이다(handoff "우회 시 사유 명기" 요구에 대한 답).
 *
 * <p><b>트랜잭션 안에서 파일 IO 를 한다</b> — 업로드 경로(MediaService)는 요청 처리 중 커넥션 점유를
 * 피하려고 IO 를 트랜잭션 밖으로 뺐지만, 여기는 기동 1회/새벽 배치라 점유 시간(로컬 디스크 수십 ms×15)이
 * 문제가 되지 않고, 실패 시 DB 전체 롤백이 더 중요하다(부분 시드가 남으면 멱등 판정이 꼬인다).
 * 파일 고아는 리셋 배치가 회수한다.
 *
 * <p>⚠️ 도메인 경계 예외: 이 클래스는 auth·core 여러 도메인의 Repository 를 직접 쓴다(§1 위반).
 * AI 분석 완료 상태·검수 확정 상태를 서비스 경유로 만들려면 실제 AI 서버 호출이 필요해 시드로 성립하지
 * 않는다 — 시드/리셋은 도메인 유스케이스가 아니라 운영 도구라 예외로 두고, 여기 한곳에만 모은다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoSeedService {

    // MediaService 의 카테고리 상수와 동일해야 서빙 엔드포인트가 시드 미디어를 구분 없이 읽는다
    // (그쪽 상수가 private 이라 부득이 중복 — 바꿀 때 양쪽을 함께 바꿀 것).
    private static final String ORIGINAL_CATEGORY = "inspection-media";
    private static final String THUMBNAIL_CATEGORY = "inspection-media-thumb";
    private static final String DETAIL_CATEGORY = "inspection-media-detail";
    private static final String LICENSE_CATEGORY = "business-registration";
    private static final Set<String> JPEG_ONLY = Set.of("image/jpeg");
    private static final long ORIGINAL_MAX_BYTES = 20_971_520L;
    private static final long THUMBNAIL_MAX_BYTES = 2_000_000L;
    private static final long DETAIL_MAX_BYTES = 8_000_000L;
    private static final int THUMBNAIL_MAX_DIMENSION = 400;
    private static final int DETAIL_MAX_DIMENSION = 1600;

    /**
     * 명백한 더미 사업자번호(#1626 P3-1) — 레포 규약대로 <b>하이픈 제거 10자리</b>
     * ({@code CompanySignupService.normalizeBrn} 과 동일 형식). 실존 불가 값(0 전부)이라 실사용
     * 회사와 절대 충돌하지 않고, unique 제약이라 환경당 1회만 시드된다.
     *
     * <p>⚠️ {@code public} 인 이유: {@code DemoResetService} 가 리셋 대상 특정의 provenance 검증
     * (#1626 P1-2)에서 이 값을 그대로 쓴다 — loginId 는 사람이 설정으로 바꾸지만 BRN·provenance 는
     * 시더만 쓰므로, 실사용 회사가 데모로 오인돼 삭제되는 것을 막는 불변식의 기준값이다.
     */
    public static final String DEMO_BUSINESS_NUMBER = "0000000000";

    /**
     * 데모 회사 provenance 표식(#1626 P1-2) — {@code companies.business_registration_ocr_raw}(jsonb)의
     * {@code "source"} 필드 값이다. 시더만 기록하는 값이라 사람 설정 오류로 흔들리지 않는다.
     *
     * <p>⚠️ 리셋 가드는 이 값을 <b>substring 매칭이 아니라 JSON 파싱</b>({@code JsonValidator.readTextField})
     * 으로 확인해야 한다(#1626 P1-A). {@code businessRegistrationOcrRaw} 는 {@code @JdbcTypeCode(JSON)}
     * String 이라, Postgres 가 jsonb 를 canonical text 로 저장하며 콜론 뒤에 공백을 넣는다
     * ({@code {"source": "DEMO_SEED"}}). 운영 리셋은 스케줄러=별도 트랜잭션=DB 재조회라 공백 포함
     * 텍스트가 오므로, {@code "source":"DEMO_SEED"}(공백 없음) substring 매칭은 <b>항상 false</b> 가 되어
     * provenance 가드가 매일 밤 리셋을 전면 무력화한다(Company#isNtsVerified 가 같은 컬럼을 파싱으로
     * 읽는 이유와 동일).
     */
    public static final String DEMO_SEED_PROVENANCE_SOURCE = "DEMO_SEED";

    /** ocr_raw 의 provenance 필드명({@code "source"}) — 시드 기록·리셋 판정이 공유. */
    public static final String DEMO_SEED_PROVENANCE_FIELD = "source";

    private static final String DEMO_COMPANY_NAME = "하자첵 데모 종합관리(주)";
    private static final String DEMO_ADMIN_NAME = "데모 관리자";

    private final DemoProperties demoProperties;
    private final PolicyProperties policyProperties;
    private final PasswordEncoder passwordEncoder;
    private final CompanyAccountWriter companyAccountWriter;
    private final UserRepository userRepository;
    private final FacilityRepository facilityRepository;
    private final InspectionRepository inspectionRepository;
    private final MediaRepository mediaRepository;
    private final DefectRepository defectRepository;
    private final ReportRepository reportRepository;
    private final FileStorageService fileStorage;

    /**
     * 최초 시드(회사·계정 + 콘텐츠). 호출부({@code DemoDataSeeder})가 "데모 계정 미존재"를 확인한 뒤
     * 호출한다 — 이메일/사업자번호 unique 제약이 경합의 최종 방어선이다(LocalUserSeeder 와 동일 구조).
     */
    @Transactional
    public void seedAll() {
        // 사업자등록증 자리에 샘플 이미지를 정식 저장 경로로 저장 — 컬럼이 not null 이고, 관리자 화면의
        // 등록증 다운로드가 404 로 깨지지 않게 실제 파일을 둔다(내용은 명백한 데모 합성 이미지).
        StoredFile license = fileStorage.storeBytes(readResource("demo/sample-crack-1.jpg"),
                "image/jpeg", LICENSE_CATEGORY, JPEG_ONLY, ORIGINAL_MAX_BYTES);

        Company company = companyAccountWriter.createAccount(
                demoProperties.getLoginId(), DEMO_ADMIN_NAME,
                passwordEncoder.encode(demoProperties.getAdminPassword()),
                DEMO_COMPANY_NAME, DEMO_BUSINESS_NUMBER,
                "서울특별시 강남구 테헤란로 100", "데모타워 10층",
                license.url(),
                "{\"" + DEMO_SEED_PROVENANCE_FIELD + "\":\"" + DEMO_SEED_PROVENANCE_SOURCE + "\"}",
                policyProperties.getTermsVersion(), policyProperties.getPrivacyVersion(),
                LocalDate.of(2020, 1, 2));

        Long adminUserId = userRepository.findByEmail(demoProperties.getLoginId())
                .orElseThrow(() -> new IllegalStateException("데모 계정 생성 직후 조회 실패 — 시드 중단"))
                .getId();

        seedContent(company.getId(), adminUserId);
        log.info("데모 시드 완료 — companyId={} adminUserId={}", company.getId(), adminUserId);
    }

    /**
     * 데모 계정 비밀번호를 설정값(env {@code DEMO_ADMIN_PASSWORD})과 동기화한다(#1626 P2-2b) — 이미
     * 시드된 데모 계정이 있는데 설정 비밀번호와 DB 해시가 어긋나면(=크레덴셜 회전) 설정을 진실 소스로
     * 재해시한다. 그렇지 않으면 회전 후 데모 로그인(서버가 설정 비밀번호로 인증)이 계정 해시와 맞지 않아
     * 깨지고, "가드는 데모 계정 비밀번호 변경을 막는데 설정만 바뀌어 로그인 불가"라는 모순이 남는다.
     *
     * <p>일치하면 no-op(불필요한 쓰기 없음). 크레덴셜이 비어 있으면 호출부가 부르지 않는다(빈 해시 방지).
     *
     * @return 재해시했으면 true
     */
    @Transactional
    public boolean syncAdminPasswordIfChanged() {
        String configured = demoProperties.getAdminPassword();
        if (configured == null || configured.isBlank()) {
            return false;
        }
        User demoAdmin = userRepository.findByEmail(demoProperties.getLoginId()).orElse(null);
        if (demoAdmin == null || !demoAdmin.hasPassword()) {
            return false;
        }
        if (passwordEncoder.matches(configured, demoAdmin.getPasswordHash())) {
            return false;
        }
        // ⚠️ 평문 비밀번호는 로그에 남기지 않는다(userId 만).
        demoAdmin.changePassword(passwordEncoder.encode(configured));
        log.info("데모 계정 비밀번호 재동기화 — 설정값 회전 감지, 해시 갱신 (userId={})", demoAdmin.getId());
        return true;
    }

    /**
     * 콘텐츠 시드(시설물·점검·미디어·완료 상태 분석 결과·보고서) — 회사·계정은 이미 존재한다는 전제.
     * 리셋({@code DemoResetService})이 방문자 데이터를 지운 뒤 이 메서드로 시드 상태를 복원한다.
     *
     * <p>데이터 구성 의도: ①시설물 3건(점검 이력 2회/1회/0회 — 목록·대시보드·통계가 골고루 채워짐)
     * ②완료 상태 분석 결과(ANALYZED 회차의 등급·bbox·측정값 채워진 하자) ③검수 확정(REVIEWED) 회차와
     * 그 보고서 초안 ④검수 미완(DETECTED) 하자 1건 — 방문자가 "검수" 흐름을 직접 체험할 거리
     * ⑤회차 간 비교(previousDefect 배선) ⑥조치 완료/진행 중 이력.
     */
    @Transactional
    public void seedContent(Long companyId, Long adminUserId) {
        LocalDate today = LocalDate.now();

        // ── 시설물 3건 ──
        Facility tower = facilityRepository.save(Facility.builder()
                .companyId(companyId).name("데모 오피스 타워").type("건물")
                .address("서울특별시 강남구 테헤란로 100").builtYear(2008).scale("지상 15층/지하 2층")
                .inspectionCycleMonths(12).nextInspectionDueAt(today.plusDays(14))
                .assigneeUserId(adminUserId).memo("데모 시드 시설물 — 정기점검 이력 2회").build());
        Facility warehouse = facilityRepository.save(Facility.builder()
                .companyId(companyId).name("데모 물류센터 A동").type("건물")
                .address("경기도 성남시 분당구 판교로 255").builtYear(1999).scale("지상 4층 창고동")
                .inspectionCycleMonths(6).nextInspectionDueAt(today.plusDays(40))
                .assigneeUserId(adminUserId).memo("데모 시드 시설물 — 분석 완료 1회").build());
        facilityRepository.save(Facility.builder()
                .companyId(companyId).name("데모 보행교").type("교량")
                .address("서울특별시 성동구 뚝섬로 273").builtYear(2005).scale("연장 85m 보도교")
                .inspectionCycleMonths(24).nextInspectionDueAt(today.plusDays(5))
                .assigneeUserId(adminUserId).memo("데모 시드 시설물 — 점검 예정(이력 없음)").build());

        // ── 시설물 대표 사진(폴리모픽 facility_id 로우) ──
        seedImage(null, tower.getId(), "demo/sample-leak-1.jpg", "tower-front.jpg");

        // ── 점검 회차: 타워 1회차(검수 확정) → 2회차(분석 완료), 물류센터 1회차(분석 완료) ──
        Inspection towerRound1 = inspectionRepository.save(Inspection.builder()
                .facilityId(tower.getId()).createdBy(adminUserId).assignedInspectorId(adminUserId)
                .roundNo(1).inspectionDate(today.minusDays(45))
                .type(InspectionType.REGULAR).status(InspectionStatus.REVIEWED).build());
        Inspection towerRound2 = inspectionRepository.save(Inspection.builder()
                .facilityId(tower.getId()).createdBy(adminUserId).assignedInspectorId(adminUserId)
                .roundNo(2).inspectionDate(today.minusDays(7))
                .type(InspectionType.REGULAR).status(InspectionStatus.ANALYZED).build());
        Inspection warehouseRound1 = inspectionRepository.save(Inspection.builder()
                .facilityId(warehouse.getId()).createdBy(adminUserId).assignedInspectorId(adminUserId)
                .roundNo(1).inspectionDate(today.minusDays(20))
                .type(InspectionType.DETAILED).status(InspectionStatus.ANALYZED).build());

        // ── 미디어(원본+썸네일+상세 3종 저장 — 정식 저장 계층) ──
        Media r1Crack = seedImage(towerRound1.getId(), null, "demo/sample-crack-2.jpg", "r1-slab-crack.jpg");
        Media r2Crack = seedImage(towerRound2.getId(), null, "demo/sample-crack-1.jpg", "r2-wall-crack.jpg");
        Media r2Leak = seedImage(towerRound2.getId(), null, "demo/sample-leak-1.jpg", "r2-ceiling-leak.jpg");
        Media whSpalling = seedImage(warehouseRound1.getId(), null, "demo/sample-spalling-1.jpg", "wh-column-spalling.jpg");

        // ── 1회차: 검수·조치까지 끝난 하자 2건(RESOLVED) — "완료 상태 AI 분석 결과 + 조치 이력" ──
        Defect r1CrackDefect = defectRepository.save(defect(towerRound1.getId(), r1Crack.getId(),
                DefectType.CRACK, DefectGrade.C, DefectStatus.RESOLVED, true,
                0.32, 0.41, 0.28, 0.12, 0.93, 0.3, 1200.0, null, "지하주차장 슬래브 중앙부"));
        r1CrackDefect.updateActionResultFields(r1Crack.getId(),
                "에폭시 수지 주입 공법으로 균열 보수 완료", today.minusDays(30), adminUserId);
        Defect r1LeakDefect = defectRepository.save(defect(towerRound1.getId(), r1Crack.getId(),
                DefectType.LEAK_EFFLORESCENCE, DefectGrade.B, DefectStatus.RESOLVED, true,
                0.55, 0.18, 0.22, 0.19, 0.88, null, null, 0.04, "지하주차장 천장 조인트 부위"));
        r1LeakDefect.updateActionResultFields(r1Crack.getId(),
                "방수층 재시공 및 백화 제거", today.minusDays(28), adminUserId);

        // ── 2회차: 분석 완료 상태 — 검수 확정 1건 + 조치 진행 중 1건 + 방문자 검수용 미확정 1건 ──
        Defect r2CrackDefect = defectRepository.save(defect(towerRound2.getId(), r2Crack.getId(),
                DefectType.CRACK, DefectGrade.C, DefectStatus.CONFIRMED, true,
                0.30, 0.44, 0.30, 0.10, 0.95, 0.4, 1450.0, null, "외벽 동측 3층 부근"));
        // 회차 간 비교(#1157) 배선 — 1회차 슬래브 균열의 진행으로 확정(검수자 확정 행위를 시드로 재현).
        r2CrackDefect.confirmPreviousDefect(r1CrackDefect.getId());

        Defect r2Progress = defectRepository.save(defect(towerRound2.getId(), r2Leak.getId(),
                DefectType.LEAK_EFFLORESCENCE, DefectGrade.B, DefectStatus.IN_PROGRESS, true,
                0.48, 0.22, 0.26, 0.21, 0.90, null, null, 0.05, "옥상층 천장 슬래브"));
        r2Progress.updateActionResultFields(r2Leak.getId(),
                "누수 경로 확인 후 1차 방수 보강 진행 중", today.minusDays(2), adminUserId);

        // 방문자 체험용 미확정(DETECTED·미검수) — AI 가 등급을 채워 넣은 직후 상태(분석 파이프라인과 동일).
        defectRepository.save(defect(towerRound2.getId(), r2Crack.getId(),
                DefectType.PAINT_DAMAGE, DefectGrade.D, DefectStatus.DETECTED, false,
                0.68, 0.61, 0.18, 0.14, 0.81, null, null, null, null));

        // ── 물류센터 1회차: 검수 확정된 박리 1건 ──
        defectRepository.save(defect(warehouseRound1.getId(), whSpalling.getId(),
                DefectType.SPALLING, DefectGrade.D, DefectStatus.CONFIRMED, true,
                0.42, 0.39, 0.24, 0.22, 0.91, null, null, 0.08, "1층 기둥 C-3 하단"));

        // ── 보고서 초안(검수 확정된 1회차 기준) — 프론트 ReportContent 계약 형태(reportDetail.mock 참조) ──
        reportRepository.save(Report.draft(towerRound1.getId(), 1, reportContentJson(), adminUserId));
    }

    private Defect defect(Long inspectionId, Long mediaId, DefectType type, DefectGrade grade,
                           DefectStatus status, boolean reviewed, double bboxX, double bboxY,
                           double bboxW, double bboxH, double confidence, Double crackWidthMm,
                           Double crackLengthMm, Double areaRatio, String location) {
        return Defect.builder()
                .inspectionId(inspectionId).mediaId(mediaId).type(type).grade(grade).status(status)
                .reviewed(reviewed).bboxX(bboxX).bboxY(bboxY).bboxW(bboxW).bboxH(bboxH)
                .confidence(confidence).crackWidthMm(crackWidthMm).crackLengthMm(crackLengthMm)
                .areaRatio(areaRatio).location(location).build();
    }

    /** 번들 이미지를 원본·썸네일·상세 3종으로 저장하고 Media 로우를 만든다(MediaService.storeAndBuild 대응). */
    private Media seedImage(Long inspectionId, Long facilityId, String resourcePath, String filename) {
        byte[] original = readResource(resourcePath);
        StoredFile originalStored = fileStorage.storeBytes(
                original, "image/jpeg", ORIGINAL_CATEGORY, JPEG_ONLY, ORIGINAL_MAX_BYTES);
        byte[] thumbnail = ImageThumbnailGenerator.generate(
                new ByteArrayInputStream(original), THUMBNAIL_MAX_DIMENSION);
        StoredFile thumbnailStored = fileStorage.storeBytes(
                thumbnail, "image/jpeg", THUMBNAIL_CATEGORY, JPEG_ONLY, THUMBNAIL_MAX_BYTES);
        byte[] detail = ImageThumbnailGenerator.generate(
                new ByteArrayInputStream(original), DETAIL_MAX_DIMENSION);
        StoredFile detailStored = fileStorage.storeBytes(
                detail, "image/jpeg", DETAIL_CATEGORY, JPEG_ONLY, DETAIL_MAX_BYTES);

        return mediaRepository.save(Media.builder()
                .inspectionId(inspectionId)
                .facilityId(facilityId)
                .fileType(MediaFileType.IMAGE)
                .originalUrl(originalStored.storageKey())
                .thumbnailUrl(thumbnailStored.storageKey())
                .detailUrl(detailStored.storageKey())
                // 서버 리소스 번들(신뢰 소스) + ImageThumbnailGenerator 디코딩 통과 — 업로드 매직바이트
                // 검증과 동일한 보증 수준이라 true 로 기록한다(클래스 javadoc 참조).
                .mimeSignatureVerified(true)
                .mimeType("image/jpeg")
                .originalFilename(filename)
                .build());
    }

    private byte[] readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("데모 시드 리소스 읽기 실패 — " + path, e);
        }
    }

    /** 프론트 {@code ReportContent}(features/report/types.ts) 계약 형태 — 시드된 1회차 하자 2건과 정합. */
    private String reportContentJson() {
        return """
                {
                  "overview": {
                    "purpose": "데모 오피스 타워 정기점검(1회차) 하자 진단 보고서 작성",
                    "facility_summary": "데모 오피스 타워 (지상 15층, 지하 2층, 2008년 준공)",
                    "scope": "주요 구조부 및 지하주차장 마감재 하자 조사"
                  },
                  "summary": {
                    "overall_opinion": "지하주차장 슬래브 건조수축 균열과 천장 조인트 부위 누수 흔적이 확인되었으나, 보수 조치가 완료되어 전체 구조 안전성에는 이상이 없습니다.",
                    "total_count": 2,
                    "count_by_grade": { "A": 0, "B": 1, "C": 1, "D": 0, "E": 0 },
                    "key_findings": [
                      "지하주차장 슬래브 중앙부 건조수축 균열(폭 0.3mm) — 에폭시 주입 보수 완료",
                      "지하주차장 천장 조인트 부위 누수·백화 — 방수층 재시공 완료"
                    ]
                  },
                  "detail": {
                    "items": [
                      {
                        "defect_type": "균열",
                        "location": "지하주차장 슬래브 중앙부",
                        "severity_grade": "C",
                        "description": "폭 0.3mm, 길이 1.2m의 건조수축 균열",
                        "cause": "콘크리트 건조수축 및 하중 작용"
                      },
                      {
                        "defect_type": "누수",
                        "location": "지하주차장 천장 조인트 부위",
                        "severity_grade": "B",
                        "description": "슬래브 조인트 부위 미세 누수 및 백화 현상",
                        "cause": "방수층 균열 및 누수 경로 형성"
                      }
                    ]
                  },
                  "recommendation": {
                    "items": [
                      {
                        "target": "슬래브 균열",
                        "method": "에폭시 수지 주입 공법 보수(완료) 후 진행성 관측",
                        "priority": "보통",
                        "legal_basis": "시설물의 안전 및 유지관리에 관한 특별법 시행령 제12조",
                        "legal_basis_verified": true
                      }
                    ],
                    "monitoring_points": [
                      "균열 게이지 설치 후 주기적 진행성 관측",
                      "우천 시 누수 부위 재발 여부 모니터링"
                    ]
                  }
                }
                """;
    }
}
