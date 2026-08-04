import notoBoldUrl from "../../../assets/fonts/NotoSansKR-Bold.subset.ttf?url";
import notoRegularUrl from "../../../assets/fonts/NotoSansKR-Regular.subset.ttf?url";
import type {
  GenericManualSectionData,
  ParticipantsSectionData,
  LocationDrawingPhotoItem,
  LocationDrawingPhotosSectionData,
  ReportContent,
  ReportDetail,
  ReportRecommendation,
  ReportSummary,
  SubmissionSectionData,
} from "../types";
import { isFixedSectionKey, resolveSectionOrder } from "./sectionOrder";

// ---------------------------------------------------------------------------
// 관공서 정밀안전진단 표준서식(한컴오피스 산출물) 조판 정합
//
// 아래 수치는 실물 제출 서식 PDF를 계측해 얻은 값이다. 추정치가 아니므로 임의로 바꾸지 말 것 —
// 흑백 인쇄 전제의 서식이라 웹 UI 관용구(브랜드색·연한 회색선·등급 색상 배지)를 섞으면
// 곧바로 "관공서 문서 같지 않은" 인상이 된다. 계측 요약:
//
//   · 폰트    맑은 고딕 Regular/Bold 단 2종. 글자색은 전 페이지 예외 없이 순수 검정.
//             (맑은 고딕은 Microsoft 소유로 재배포 불가 → 같은 휴머니스트 산세리프 계열의
//              Noto Sans KR(본고딕, OFL)로 대체. 서브셋 생성은 scripts/build-pdf-fonts.py)
//   · 크기    문서 제목 25 / 부속 제목 20 / 절 제목 14 / 소절 11 / 표 헤더·본문 10 / 좁은 셀 9 (pt)
//   · 회색    표 헤더·라벨 배경 #CCCCCC. 그 외 배경은 흰색.
//   · 괘선    전부 검정. 표 내부 0.36pt, 표 외곽 1.08pt — 외곽이 내부의 3배.
//   · 여백    좌우 65.2pt = 23mm 대칭, 본문폭 164mm.
//   · 없는 것 머리말·꼬리말·페이지번호·로고·워터마크·강조색.
//
// 섹션 순서는 편집기(ReportContentEditor)에서 자유롭게 바꿀 수 있고(제출문·참여기술진 명단 등
// 수동 섹션 포함), resolveSectionOrder가 편집기·PDF 양쪽의 단일 SOT다. 순서가 유동적이어도
// 원본처럼 여러 소절을 한 페이지에 채운다 — 섹션마다 남는 공간에 이어 쓰고, 안 들어갈 때만
// 새 페이지로 넘긴다(ensureSpace). 제출문만 예외: formal한 커버 페이지 관용구라 항상 단독
// 페이지를 쓴다(전/후 모두 새 페이지).
// ---------------------------------------------------------------------------

const FONT_NAME = "NotoSansKR";
const REGULAR_FONT_FILE = "NotoSansKR-Regular.ttf";
const BOLD_FONT_FILE = "NotoSansKR-Bold.ttf";

const PAGE_WIDTH = 210;
const PAGE_HEIGHT = 297;
const MARGIN_X = 23;
const CONTENT_WIDTH = PAGE_WIDTH - MARGIN_X * 2;
/**
 * 소절 제목(`가.`, `나.`)만 본문보다 약 3.5mm 들여쓴다. 표는 들여쓰지 않는다 — 원본은 절·소절을
 * 가리지 않고 모든 표가 같은 본문 폭(좌우 여백 23mm)을 쓰고, 제목만 살짝 안으로 들어간다.
 */
const SUB_TABLE_INDENT = 3.5;
const BOTTOM_LIMIT = PAGE_HEIGHT - MARGIN_X;
/** 부위별 사진 표에서 사진 1장이 차지하는 셀 높이(원본 실측 96mm). */
const PHOTO_ROW_HEIGHT = 96;
/** 절 제목 한 줄이 차지하는 높이(sectionTitle이 커서를 밀어내는 양). */
const SECTION_TITLE_HEIGHT = 6;
/** 사진 아래 캡션 행 높이. */
const PHOTO_CAPTION_HEIGHT = 9;
/** 사진 1장 표(이미지 행 + 캡션 행)가 통째로 들어가야 하는 높이 — 이만큼 없으면 페이지를 넘긴다. */
const PHOTO_BLOCK_HEIGHT = PHOTO_ROW_HEIGHT + 4 + PHOTO_CAPTION_HEIGHT;

const FONT_SIZE = {
  documentTitle: 25,
  submissionTitle: 24,
  sectionTitle: 14,
  subsectionTitle: 11,
  recipient: 15,
  body: 12.9,
  table: 10,
  tableNarrow: 9,
  caption: 10,
} as const;

const BLACK: [number, number, number] = [0, 0, 0];
/** 표 헤더·라벨 배경. 원본 계측 0.8 → 204. */
const HEAD_FILL: [number, number, number] = [204, 204, 204];
/**
 * 회색이 들어가는 자리(표 헤더 행·표 위 구분 행·좌측 라벨 열)는 전부 이 한 벌을 쓴다 —
 * 절마다 다른 회색·정렬이 섞이면 같은 문서에서 톤이 달라 보인다(원본은 #CCCCCC·Bold·중앙 하나뿐).
 */
const GRAY_HEADER_STYLES = {
  fillColor: HEAD_FILL,
  fontStyle: "bold" as const,
  halign: "center" as const,
};
// 하자 박스 색(#1333) — 흑백 괘선뿐인 관공서 서식에서 사진 위 마킹만 눈에 띄어야 하므로
// 화면 오버레이(--color-selection, 마젠타 #d946ef)와 같은 색을 쓴다.
const BOX_COLOR: [number, number, number] = [217, 70, 239];

const PT_TO_MM = 25.4 / 72;
/** 표 내부 괘선 0.36pt. */
const LINE_INNER = 0.36 * PT_TO_MM;
/** 표 외곽 테두리 1.08pt. */
const LINE_OUTER = 1.08 * PT_TO_MM;

export interface ReportPdfContext {
  facilityName?: string;
  inspectionRound?: number;
  issuedAt?: Date;
  responsibleEngineerName?: string;
  defectImages?: ReportPdfImage[];
}

/** PDF 사진 위에 그릴 하자 박스 — 0~1 정규화 좌표(이미지 좌상단 기준). */
export interface ReportPdfBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ReportPdfImage {
  defectType: string;
  imageUrl: string;
  /** 하자 등급(A~E) — 캡션에 "유형(등급)"으로 함께 표기해 사진만 보고도 심각도를 알 수 있게 한다. */
  grade?: string;
  /** AI/점검자 분석 요약 — 캡션에 짧게 덧붙여 "균열" 같은 유형명 단독 표기를 피한다. */
  summary?: string;
  /** 이 사진에서 탐지된 하자 박스(#1333). 비어 있으면 사진만 그린다. */
  boxes?: ReportPdfBox[];
  /** 하위호환 필드. PDF는 하자별·등급별 항목으로 분리하므로 캡션 합산에는 쓰지 않는다. */
  defectCount?: number;
}

const PHOTO_CAPTION_SUMMARY_MAX = 40;

/** 원본 서식의 소절 번호(`가.`, `나.` …). */
const KOREAN_ORDINALS = [
  "가",
  "나",
  "다",
  "라",
  "마",
  "바",
  "사",
  "아",
  "자",
  "차",
] as const;

/**
 * `2. 결과 요약`의 소절로 들어가는 섹션 종류 — 원본은 진단 외관조사결과·상태평가·안전성평가·
 * 현장시험을 모두 `2. 결과 요약` 아래 `가./나./다./라.`로 묶는다(별도 절 번호를 주지 않는다).
 * 섹션 순서·구성은 그대로 두고 번호 표기만 이 규칙에 맞춘다.
 */
const SUMMARY_SUBSECTION_TYPES = new Set<string>([
  "detail",
  "recommendation",
  "inspection-result-repair",
  "member-condition-repair",
  "safety-assessment",
  "field-test",
]);

/**
 * 사진 캡션은 유형명만 단독으로 쓰지 않는다 — "균열"만으로는 어느 사진인지 구별이 안 된다.
 * grade·summary는 같은 하자 레코드(Defect)에서 thumbnailUrl과 함께 나온 값이라(별도 매칭 없이
 * 그대로 짝지어 넘어옴) 오표기 위험 없이 안전하게 붙일 수 있다.
 */
function formatPhotoCaption(image: ReportPdfImage): string {
  const type = image.defectType || "부위";
  const gradeSuffix = image.grade ? `(${image.grade}등급)` : "";
  const summary = (image.summary ?? "").trim();
  if (!summary) return `${type}${gradeSuffix}`;
  const truncated =
    summary.length > PHOTO_CAPTION_SUMMARY_MAX
      ? `${summary.slice(0, PHOTO_CAPTION_SUMMARY_MAX)}…`
      : summary;
  return `${type}${gradeSuffix} — ${truncated}`;
}

/**
 * jspdf-autotable의 CellHookData 중 실제로 쓰는 필드만 뽑은 최소 타입 — jspdf-autotable을
 * 동적 import(`await import('jspdf-autotable')`)로 쓰고 있어 정적 타입을 끌어오지 않는다.
 * 사진 표에서 셀이 실제로 어느 페이지·좌표에 그려졌는지(자동 페이지분할 이후 값)를 읽어
 * 그 자리에 이미지를 겹쳐 그리는 용도다.
 */
interface AutoTableCellHookData {
  section: "head" | "body" | "foot";
  row: { index: number };
  cell: { x: number; y: number; width: number; height: number };
}

async function toBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = reader.result as string;
      resolve(result.slice(result.indexOf(",") + 1));
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function formatDate(date: Date): string {
  return `${date.getFullYear()}년 ${String(date.getMonth() + 1).padStart(2, "0")}월 ${String(date.getDate()).padStart(2, "0")}일`;
}

function formatOptionalDate(date?: Date): string {
  return date ? formatDate(date) : "-";
}

function normalizeGradeCount(
  countByGrade: Record<string, number>,
  grade: string,
): string {
  return String(countByGrade[grade] ?? 0);
}

/**
 * 부재별 상태평가 등급은 표준서식에서 **소문자** a~e로 적는다(대문자 A~E는 시설물 종합
 * 안전등급 전용). `defects.grade` enum 은 대문자로 저장되므로 표시 계층에서만 변환한다 —
 * 저장 값을 바꾸는 것이 아니다.
 */
function toMemberGrade(grade: string): string {
  const normalized = grade.trim();
  return /^[A-Ea-e]$/.test(normalized)
    ? normalized.toLowerCase()
    : normalized || "-";
}

/** 등급별 건수에서 최악(가장 심각) 등급을 뽑아 "상태평가 결과"로 쓴다. E가 가장 심각. */
function worstGrade(countByGrade: Record<string, number>): string {
  const worst = (["E", "D", "C", "B", "A"] as const).find(
    (grade) => (countByGrade[grade] ?? 0) > 0,
  );
  return worst ? worst.toLowerCase() : "-";
}

function legalBasisLabel(legalBasis: string, verified: boolean): string {
  const basis = legalBasis || "관련 근거 없음";
  return verified ? basis : `${basis} (미검증)`;
}

function formatResponsibleEngineerName(name?: string): string {
  const normalized = (name ?? "").trim();
  if (!normalized) return "";
  if (normalized.includes(" ")) return normalized;
  return /^[가-힣]{2,4}$/.test(normalized)
    ? normalized.split("").join(" ")
    : normalized;
}

/**
 * 문서 전체에서 목록 표기는 이 불릿 하나로만 한다 — 절마다 `•`/`-`/`1)`/`//`가 섞이면
 * 같은 보고서 안에서 문단 표기가 제멋대로로 보인다(원본도 표 안 목록은 `ㆍ` 하나뿐).
 */
const BULLET = "ㆍ";

/** 셀 안의 목록은 `ㆍ` 불릿을 붙여 줄바꿈으로 나열한다(번호 없음). */
function toBulletCell(
  values: string[],
  fallback: string,
  separator = "\n",
): string {
  return values.length > 0
    ? values.map((value) => `${BULLET}${value}`).join(separator)
    : fallback;
}

/**
 * 원본 1.나 첫 행 `중대한 결함 등`. 시설물안전법상 "중대한 결함"은 우리 데이터에 별도 플래그가
 * 없으므로, 판정 근거가 분명한 최하위 등급(d·e)만 뽑아 나열하고 없으면 원본 관용구대로 `없음`.
 */
function criticalDefectSummary(detail: ReportDetail): string {
  const severe = detail.items.filter((item) =>
    ["D", "E"].includes(item.severity_grade.trim().toUpperCase()),
  );
  if (severe.length === 0) return "없음";
  return toBulletCell(
    severe.map(
      (item) =>
        `${item.location || "위치 미기재"} ${item.defect_type || "결함"}(${toMemberGrade(item.severity_grade)}등급)`,
    ),
    "없음",
  );
}

/**
 * 원본 1.나 `점검 주요결과` — 부재별로 `//부재 1)유형 n건` 형태로 묶어 적는다(원본은 수량을
 * ㎡·m로 적지만 우리는 물량을 수집하지 않으므로 건수로 센다).
 */
function inspectionResultSummary(content: ReportContent): string {
  const byLocation = new Map<string, Map<string, number>>();
  for (const item of content.detail.items) {
    const location = item.location?.trim() || "부재 미기재";
    const type = item.defect_type?.trim() || "결함";
    const types = byLocation.get(location) ?? new Map<string, number>();
    types.set(type, (types.get(type) ?? 0) + 1);
    byLocation.set(location, types);
  }
  if (byLocation.size === 0) return "확인된 결함이 없습니다.";
  const lines = [...byLocation].map(
    ([location, types]) =>
      `${location} : ${[...types]
        .map(([type, count]) => `${type} ${count}건`)
        .join(", ")}`,
  );
  // 문장 종결은 AI가 생성하는 본문(존댓말)에 맞춘다 — 한 보고서 안에서 평서체와 섞이지 않게.
  return `금회 조사 결과 주요 결함은 다음과 같습니다.\n${toBulletCell(lines, "")}`;
}

/** 원본 1.나 `주요 보수ㆍ보강` — 조치 우선순위별로 묶어 `-1순위 : 공법, 공법` 형태로 적는다. */
function majorRepairSummary(recommendation: ReportRecommendation): string {
  const byPriority = new Map<string, string[]>();
  for (const item of recommendation.items) {
    const priority = item.priority?.trim() || "우선순위 미지정";
    const method = item.method?.trim();
    if (!method) continue;
    byPriority.set(priority, [...(byPriority.get(priority) ?? []), method]);
  }
  if (byPriority.size === 0) return "해당 없음";
  return toBulletCell(
    [...byPriority].map(
      ([priority, methods]) => `${priority} : ${[...new Set(methods)].join(", ")}`,
    ),
    "해당 없음",
  );
}

/**
 * `2. 결과 요약` 본문 한 칸. 원본은 이 절만 소절로 나누지 않고 종합의견을 문단 불릿으로 죽
 * 나열한다 — 불릿은 문서 공용 표기(`ㆍ`)를 쓰고 문단 사이만 한 줄 띄운다.
 * 그래서 소절 표로 따로 뽑던 주요 발견사항·등급별 건수도 같은 불릿 흐름에 이어 붙이되,
 * 건수는 원본 문체(서술형 종결)에 맞춰 한 문장으로 적는다 — 새 데이터 없이 표기만 정합화.
 */
function buildSummaryOpinionCell(summary: ReportSummary): string {
  const paragraphs = summary.overall_opinion
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  paragraphs.push(
    ...summary.key_findings.map((finding) => finding.trim()).filter(Boolean),
  );
  if (summary.total_count > 0) {
    const breakdown = (["A", "B", "C", "D", "E"] as const)
      .map(
        (grade) =>
          `${grade.toLowerCase()} ${normalizeGradeCount(summary.count_by_grade, grade)}건`,
      )
      .join(", ");
    paragraphs.push(
      `금회 조사 결과 확인된 결함은 총 ${summary.total_count}건으로, 등급별로는 ${breakdown}으로 조사되었습니다.`,
    );
  }
  // 문단 사이만 한 줄 띄우고, 불릿은 문서 공용 표기(`ㆍ`)를 그대로 쓴다.
  return toBulletCell(paragraphs, "종합의견이 작성되지 않았습니다.", "\n\n");
}

async function loadPdfImage(
  imageUrl: string,
): Promise<{ dataUrl: string; format: "JPEG" | "PNG" } | null> {
  try {
    const response = await fetch(imageUrl, { credentials: "include" });
    if (!response.ok) return null;
    const blob = await response.blob();
    const format =
      blob.type === "image/png"
        ? "PNG"
        : blob.type === "image/jpeg"
          ? "JPEG"
          : null;
    if (!format) return null;
    return {
      dataUrl: await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onloadend = () => resolve(reader.result as string);
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(blob);
      }),
      format,
    };
  } catch {
    return null;
  }
}

// normalizePdfPreviewUrl / buildReportPdfFileName은 features/mypage(MyReportListItem)에서도
// 필요해져 shared/utils/reportPdf.ts로 승격됐다(#1472, feature 간 직접 import 금지 컨벤션).
// 소비처(ReportListPage, ReportGeneratePage 등)는 재수출 없이 shared에서 바로 import한다.

// 표준서식의 구성(제출문 → 결과표 → 결과 요약 → 진단 외관조사결과 기본사항 → 보수ㆍ보강(안) → 참여 기술진 명단
// → 부위별 사진)을 따르되, 실제 순서는 편집기에서 사용자가 정한 sectionOrder를 그대로 따른다.
// 안전성평가(SF)·현장시험·위치도처럼 대응 데이터가 아예 없는 원본 섹션은 빈 표를 만들지 않고
// 제외한다 — 점검관리 플로우가 수집하지 않는 값이라 채울 방법이 없다.
export async function exportReportToPdf(
  content: ReportContent,
  context: ReportPdfContext = {},
): Promise<Blob> {
  const [
    { default: jsPDF },
    { default: autoTable },
    regularFontResponse,
    boldFontResponse,
  ] = await Promise.all([
    import("jspdf"),
    import("jspdf-autotable"),
    fetch(notoRegularUrl),
    fetch(notoBoldUrl),
  ]);
  const [regularFontBase64, boldFontBase64] = await Promise.all([
    toBase64(await regularFontResponse.blob()),
    toBase64(await boldFontResponse.blob()),
  ]);

  const doc = new jsPDF({ unit: "mm", format: "a4" });
  doc.addFileToVFS(REGULAR_FONT_FILE, regularFontBase64);
  doc.addFont(REGULAR_FONT_FILE, FONT_NAME, "normal");
  doc.addFileToVFS(BOLD_FONT_FILE, boldFontBase64);
  doc.addFont(BOLD_FONT_FILE, FONT_NAME, "bold");
  doc.setFont(FONT_NAME, "normal");
  doc.setTextColor(...BLACK);
  doc.setDrawColor(...BLACK);
  // 원본은 줄간격이 좁다(10pt 본문에 행높이 약 17pt).
  doc.setLineHeightFactor(1.35);

  const facilityName =
    context.facilityName ||
    content.overview.facility_summary ||
    "시설물명 미기재";
  const inspectionLabel = context.inspectionRound
    ? `제${context.inspectionRound}회차`
    : "-";
  const loadedDefectImages = await Promise.all(
    (context.defectImages ?? []).map(async (image) => {
      const loaded = await loadPdfImage(image.imageUrl);
      return loaded ? { ...image, ...loaded } : null;
    }),
  );
  // 사진은 점검 API가 준 축소본만 쓴다(새로 만들지 않음). 렌더 순서(sectionOrder)와 무관하게
  // 필요하므로 다른 섹션 render 함수들보다 먼저 확정해 둔다.
  const photoEntries = loadedDefectImages.filter(
    (
      image,
    ): image is ReportPdfImage & { dataUrl: string; format: "JPEG" | "PNG" } =>
      image !== null,
  );

  const tableDefaults = {
    theme: "grid" as const,
    margin: {
      left: MARGIN_X,
      right: MARGIN_X,
      top: MARGIN_X,
      bottom: MARGIN_X,
    },
    rowPageBreak: "avoid" as const,
    showHead: "everyPage" as const,
    // 외곽 테두리를 내부 괘선의 3배로 — 관공서 표의 인상을 만드는 핵심 대비.
    tableLineColor: BLACK,
    tableLineWidth: LINE_OUTER,
    styles: {
      font: FONT_NAME,
      fontStyle: "normal" as const,
      fontSize: FONT_SIZE.table,
      cellPadding: { top: 1.3, right: 1.6, bottom: 1.3, left: 1.6 },
      lineColor: BLACK,
      lineWidth: LINE_INNER,
      textColor: BLACK,
      valign: "middle" as const,
      overflow: "linebreak" as const,
    },
    headStyles: {
      font: FONT_NAME,
      ...GRAY_HEADER_STYLES,
      textColor: BLACK,
      lineWidth: LINE_INNER,
    },
  };

  /** 좌측 라벨 열 — 회색 관용구는 헤더 행과 동일하게 쓴다. */
  const labelColumn = (cellWidth: number) => ({
    cellWidth,
    ...GRAY_HEADER_STYLES,
  });

  const lastTableY = () =>
    (doc as typeof doc & { lastAutoTable: { finalY: number } }).lastAutoTable
      .finalY;

  /** 절 제목 — `1. 기본현황` Bold 14pt. 원본에는 하단 구분선이 없다. */
  const sectionTitle = (label: string, y: number) => {
    doc.setFont(FONT_NAME, "bold");
    doc.setFontSize(FONT_SIZE.sectionTitle);
    doc.text(label, MARGIN_X, y);
    doc.setFont(FONT_NAME, "normal");
    return y + 6;
  };

  /** 소절 제목 — `가. 일반현황` Bold 11pt. */
  const subsectionTitle = (label: string, y: number) => {
    doc.setFont(FONT_NAME, "bold");
    doc.setFontSize(FONT_SIZE.subsectionTitle);
    doc.text(label, MARGIN_X + SUB_TABLE_INDENT, y);
    doc.setFont(FONT_NAME, "normal");
    return y + 4.2;
  };

  /**
   * 원본은 깊이마다 번호 체계를 바꾼다 — 절 `1.` → 소절 `가.` → 소소절 `1)`.
   * `2. 결과 요약` 아래로 들어가는 블록(nested)은 절 제목 대신 소절 제목으로 찍고,
   * 그 블록이 자체적으로 갖고 있던 `가./나.`는 한 단계 더 내려 `1)/2)`로 적는다.
   */
  const blockTitle = (label: string, y: number, nested: boolean) =>
    nested ? subsectionTitle(label, y) : sectionTitle(label, y);

  const childMarker = (nested: boolean, index: number) =>
    nested ? `${index + 1})` : `${KOREAN_ORDINALS[index] ?? "기타"}.`;

  /** 남은 지면이 부족하면 새 페이지로 넘긴다(소절 제목이 페이지 끝에 홀로 남는 것 방지). */
  const ensureSpace = (y: number, needed: number) => {
    if (y + needed <= BOTTOM_LIMIT) return y;
    doc.addPage();
    return MARGIN_X;
  };

  // ── 1. 기본현황 ──────────────────────────────────────────────────────────
  const renderOverviewBlock = (label: string, startY: number): number => {
    let y = sectionTitle(label, startY);
    y = subsectionTitle("가. 일반현황", y);

    // 원본의 2단 라벨-값 표(`구 분 | 내 용 | 구 분 | 내 용`) — 지면을 절약하는 서식 관용구.
    // 라벨 텍스트에 넣은 공백은 원본이 글자수를 맞추는 방식이라 그대로 따른다.
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      body: [
        ["시 설 물 명", facilityName, "점검 회차", inspectionLabel],
        [
          "작성 기준일",
          formatOptionalDate(context.issuedAt),
          "확인 결함",
          `${content.summary.total_count}건`,
        ],
      ],
      columnStyles: {
        0: labelColumn(28),
        1: { cellWidth: 55 },
        2: labelColumn(24),
        3: { cellWidth: "auto" },
      },
    });

    // 원본 1.나는 "점검 개요"가 아니라 `점검 실시결과 현황` — 중대한 결함 / 공중이 이용하는
    // 부위의 결함 / 점검 주요결과 / 주요 보수ㆍ보강 4행짜리 라벨 표다. 네 값 모두 이미 있는
    // content(하자 목록·권고 조치)에서 파생할 수 있어 스키마 변경 없이 서식만 맞춘다.
    y = subsectionTitle("나. 점검 실시결과 현황", lastTableY() + 6);
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      body: [
        ["중대한 결함 등", criticalDefectSummary(content.detail)],
        // 하자 데이터엔 "공중이 이용하는 부위"(보도·난간 등) 여부 구분이 없어 자동 판정할 수
        // 없다 — 근거 없이 "없음"이라 단정하면 허위가 되므로, 편집기(1.기본현황)에서 점검자가
        // 직접 입력한 값만 쓰고 미입력이면 "-"로 표기한다.
        [
          "공중이 이용하는\n부위의 결함",
          content.overview.public_use_area_defect?.trim() || "-",
        ],
        ["점검 주요결과", inspectionResultSummary(content)],
        ["주요 보수ㆍ보강", majorRepairSummary(content.recommendation)],
      ],
      columnStyles: { 0: labelColumn(28), 1: { cellWidth: "auto" } },
      bodyStyles: { valign: "top", halign: "left" },
    });

    // 원본 1.다·1.라(참여기술자·참고사항)에 해당하는 자리. 참여기술진은 별도 수동 섹션이라
    // 여기서는 점검 목적·개요·범위를 참고사항으로 남긴다(기존 "나. 점검 개요"의 내용).
    y = subsectionTitle("다. 참고사항", ensureSpace(lastTableY() + 6, 30));
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      body: [
        ["점검 목적", content.overview.purpose || "-"],
        ["시설물 개요", content.overview.facility_summary || "-"],
        ["점검 범위", content.overview.scope || "-"],
      ],
      columnStyles: { 0: labelColumn(28), 1: { cellWidth: "auto" } },
      bodyStyles: { valign: "top", halign: "left" },
    });
    return lastTableY();
  };

  // ── 2. 결과 요약 ─────────────────────────────────────────────────────────
  // 원본은 이 절만 소절(가./나./다.)로 쪼개지 않는다 — 절 제목 바로 아래에 헤더 한 칸
  // (`책임기술자 종합의견`)짜리 표가 본문 폭 전체로 놓이고, 그 안에 의견 불릿이 이어지다
  // 우측 하단에 서명란이 붙는 단일 구성이다. 소절이 없으니 표도 들여쓰지 않는다(subTable 미적용).
  // 기존 소절이 담던 등급별 건수·주요 발견사항은 표를 따로 만들지 않고 같은 불릿 흐름에 흡수한다
  // — 새 데이터를 붙이지 않고 표시 구조만 원본에 맞춘다(#1409).
  const renderSummaryBlock = (label: string, startY: number): number => {
    const y = sectionTitle(label, startY);
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      head: [["책임기술자 종합의견"]],
      // 서명은 본문 셀에 겹쳐 그리지 않고 아래 행으로 분리한다 — 의견이 길어지면 마지막 줄과
      // 겹친다. 본문·서명 두 행 모두 괘선을 지워(lineWidth 0) 원본처럼 칸막이 없는 한 상자로
      // 보이게 하고, 상자 테두리는 tableLineWidth(외곽)가 그대로 그린다.
      body: [
        [{ content: buildSummaryOpinionCell(content.summary), styles: { minCellHeight: 40 } }],
        [
          {
            content: `책임기술자 : ${formatResponsibleEngineerName(content.summary.responsible_engineer_name ?? context.responsibleEngineerName)}    (서명)`,
            styles: { halign: "right" as const, minCellHeight: 12 },
          },
        ],
      ],
      bodyStyles: { valign: "top", halign: "left", lineWidth: 0 },
    });
    return lastTableY();
  };

  // ── 3. 진단 외관조사결과 ─────────────────────────────────────────────────
  const renderDetailBlock = (
    label: string,
    startY: number,
    nested = false,
  ): number => {
    const y = blockTitle(label, startY, nested);
    // 원본은 표 위에 "상태평가 결과 : b" 를 회색 배경 한 행으로 얹는다. 등급별 건수에서
    // 최악 등급을 뽑아 같은 자리에 채운다(새 데이터 요구 없음).
    //
    // 이 바를 별도 autoTable로 분리하면 jsPDF-autotable이 페이지 넘김 시 자동 반복시키는
    // `head`에 포함되지 않아, 표가 여러 페이지에 걸칠 때 첫 페이지에만 나오고 사라진다(한글
    // "표 제목 줄 자동 반복" 관용구와 어긋남). 아래 컬럼 헤더 행과 같은 표의 `head` 첫 행으로
    // 합쳐서 페이지마다 함께 반복되게 한다 — colSpan은 6개 컬럼을 3:3으로 나눠 원본의
    // "라벨:값" 2분할을 근사한다(정확한 폭 비율은 원본과 다를 수 있으나 순수 장식용 바라 무관).
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      head: [
        [
          { content: "상태평가 결과 및 보수ㆍ보강", colSpan: 3 },
          {
            content: `상태평가 결과 : ${worstGrade(content.summary.count_by_grade)}`,
            colSpan: 3,
          },
        ],
        [
          "연번",
          "결함발생 부재",
          "상태\n평가",
          "결함종류",
          "조사 결과",
          "추정 원인",
        ],
      ],
      body:
        content.detail.items.length > 0
          ? content.detail.items.map((item, index) => [
              String(index + 1),
              item.location || "-",
              toMemberGrade(item.severity_grade),
              item.defect_type || "-",
              item.description || "-",
              item.cause || "-",
            ])
          : [["-", "-", "-", "-", "확인된 결함이 없습니다.", "-"]],
      // 좁은 열은 원본처럼 9pt로 강등한다.
      columnStyles: {
        0: { cellWidth: 11, halign: "center", fontSize: FONT_SIZE.tableNarrow },
        1: { cellWidth: 26 },
        2: { cellWidth: 12, halign: "center" },
        3: { cellWidth: 24 },
        4: { cellWidth: "auto" },
        5: { cellWidth: 38 },
      },
    });
    return lastTableY();
  };

  // ── 4. 보수ㆍ보강(안) ────────────────────────────────────────────────────
  const renderRecommendationBlock = (
    label: string,
    startY: number,
    nested = false,
  ): number => {
    // 보수ㆍ보강(안) 표는 소절 제목을 따로 달지 않는다 — 블록 제목이 이미 `보수ㆍ보강(안)`이라
    // 소절까지 같은 이름을 붙이면 같은 문구가 연달아 두 번 나온다.
    let y = blockTitle(label, startY, nested);
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      head: [
        ["연번", "대상 부위", "보수ㆍ보강(안)", "조치\n우선순위", "적용 근거"],
      ],
      body:
        content.recommendation.items.length > 0
          ? content.recommendation.items.map((item, index) => [
              String(index + 1),
              item.target || "-",
              item.method || "-",
              item.priority || "-",
              legalBasisLabel(item.legal_basis, item.legal_basis_verified),
            ])
          : [["-", "-", "권고 조치가 없습니다.", "-", "-"]],
      columnStyles: {
        0: { cellWidth: 11, halign: "center", fontSize: FONT_SIZE.tableNarrow },
        1: { cellWidth: 30 },
        2: { cellWidth: "auto" },
        3: { cellWidth: 18, halign: "center", fontSize: FONT_SIZE.tableNarrow },
        4: { cellWidth: 44 },
      },
    });

    y = subsectionTitle(
      `${childMarker(nested, 0)} 지속 관찰 부위`,
      ensureSpace(lastTableY() + 6, 30),
    );
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      body: [
        [
          toBulletCell(
            content.recommendation.monitoring_points,
            "지속 관찰이 필요한 부위가 없습니다.",
          ),
        ],
      ],
      bodyStyles: { valign: "top", halign: "left" },
    });
    return lastTableY();
  };

  // ── 제출문(수동 섹션) ────────────────────────────────────────────────────
  // 계약 당사자·수신 기관명은 하자점검 도메인 밖 값이라 백엔드가 생산할 수 없다 — 편집기에서
  // 사용자가 직접 입력한 값을 그대로 배치한다. 원본 관용구: 24pt Bold 중앙 제목, 수신자
  // 좌측 상단, 본문 좌측, 발신 정보는 우측 정렬. 늘 단독 페이지(커버 페이지 관용구)이므로
  // startY는 항상 페이지 시작점(MARGIN_X)이다.
  const renderSubmissionBlock = (
    data: SubmissionSectionData,
    startY: number,
  ): number => {
    doc.setFont(FONT_NAME, "bold");
    doc.setFontSize(FONT_SIZE.submissionTitle);
    doc.text("제  출  문", PAGE_WIDTH / 2, startY + 6, { align: "center" });
    doc.setFont(FONT_NAME, "normal");

    let y = startY + 28;
    doc.setFont(FONT_NAME, "bold");
    doc.setFontSize(FONT_SIZE.recipient);
    doc.text(data.recipient || "수신자 미기재", MARGIN_X, y);
    doc.setFont(FONT_NAME, "normal");

    y += 14;
    doc.setFontSize(FONT_SIZE.body);
    const paragraph =
      `귀 기관과 ${data.contractDate || "-"} 계약 체결한 "${facilityName} 정밀안전점검"에 대한 ` +
      "결과를 본 보고서에 수록하여 제출합니다.";
    const wrapped = doc.splitTextToSize(paragraph, CONTENT_WIDTH) as string[];
    doc.text(wrapped, MARGIN_X, y);
    y += wrapped.length * 6.5 + 50;

    doc.setFontSize(FONT_SIZE.table + 2);
    doc.text(formatOptionalDate(context.issuedAt), PAGE_WIDTH - MARGIN_X, y, {
      align: "right",
    });

    y += 16;
    doc.text(data.companyAddress || "-", PAGE_WIDTH - MARGIN_X, y, {
      align: "right",
    });

    y += 8;
    doc.setFont(FONT_NAME, "bold");
    doc.text(data.companyName || "-", PAGE_WIDTH - MARGIN_X, y, {
      align: "right",
    });

    y += 8;
    doc.text(
      `대표자  ${data.representativeName || "-"} (인)`,
      PAGE_WIDTH - MARGIN_X,
      y,
      { align: "right" },
    );
    doc.setFont(FONT_NAME, "normal");
    return y;
  };

  // ── 참여기술진 명단(수동 섹션) ───────────────────────────────────────────
  // 참여자 실명·자격은 마찬가지로 도메인 밖 값. 원본은 표 형태(구분/성명/자격 및 주요경력/기간).
  const renderParticipantsBlock = (
    label: string,
    data: ParticipantsSectionData,
    startY: number,
  ): number => {
    const y = sectionTitle(label, startY);
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      head: [["구  분", "성  명", "자격 및 주요경력", "과업 참여기간"]],
      body:
        data.entries.length > 0
          ? data.entries.map((entry) => [
              entry.role || "-",
              entry.name || "-",
              entry.qualification || "-",
              entry.period || "-",
            ])
          : [["-", "-", "참여기술진 정보가 없습니다.", "-"]],
      columnStyles: {
        0: { cellWidth: 32, halign: "center" },
        1: { cellWidth: 28, halign: "center" },
        2: { cellWidth: "auto" },
        3: { cellWidth: 48, halign: "center" },
      },
    });
    return lastTableY();
  };

  // ── 일반 수동 섹션 ──────────────────────────────────────────────────────
  // 안전성평가 결과·현장시험·도면류처럼 DB/AI 스키마에 없는 항목은 사용자가 입력한 본문을 같은
  // 관공서 표 양식으로 싣는다. 새 컬럼 없이 reports.content_json 안에서만 왕복된다.
  const renderGenericManualBlock = (
    label: string,
    data: GenericManualSectionData,
    startY: number,
    nested = false,
  ): number => {
    const y = blockTitle(label, startY, nested);
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      body: [[data.body?.trim() || "입력된 내용이 없습니다."]],
      bodyStyles: { minCellHeight: 24, valign: "top", halign: "left" },
    });
    return lastTableY();
  };

  // ── 부위별 사진(고정 섹션) ───────────────────────────────────────────────
  // 사진 1장 = "이미지 행 + 캡션 행" 2행짜리 표 한 칸으로 넣는다(원본 "전경사진" 관용구 그대로:
  // 사진 아래 캡션이 같은 테두리 안에 있음). 좌표를 직접 계산해 doc.addImage+doc.rect로 쌓던
  // 이전 방식은 사진이 페이지 하단을 넘어가도 그대로 잘려 나갔다 — autoTable의
  // rowPageBreak:'avoid'에 맡기면 표가 알아서 다음 페이지로 넘겨 "표 안에 안전하게" 들어간다.
  // 이미지 자체는 didDrawCell에서 그리는데, 이 콜백이 받는 cell 좌표가 이미 자동 페이지분할
  // 이후의 실제 위치이기 때문이다.
  const renderPhotosBlock = (label: string, startY: number): number => {
    if (photoEntries.length === 0) return startY;
    // 절 제목만 페이지 끝에 남고 사진이 다음 장으로 넘어가지 않도록, 제목 높이까지 합쳐 자리를 본다.
    let y = sectionTitle(
      label,
      ensureSpace(startY, SECTION_TITLE_HEIGHT + PHOTO_BLOCK_HEIGHT),
    );
    // 사진 1장 = 표 1개(이미지 행 + 캡션 행). 한 표에 전부 몰아넣으면 이미지 행이 다음 페이지로
    // 밀릴 때 표 윗선·캡션만 현재 페이지에 남아 "선 하나만 걸친" 페이지가 생긴다. 장마다 표를
    // 끊고 들어갈 자리(PHOTO_BLOCK_HEIGHT)가 없으면 먼저 페이지를 넘겨 그 상황 자체를 없앤다.
    photoEntries.forEach((entry) => {
      y = ensureSpace(y, PHOTO_BLOCK_HEIGHT);
      renderPhotoTable(entry, y);
      y = lastTableY();
    });
    return y;
  };

  const renderPhotoTable = (
    entry: (typeof photoEntries)[number],
    startY: number,
  ): void => {
    autoTable(doc, {
      ...tableDefaults,
      startY,
      tableWidth: CONTENT_WIDTH,
      body: [
        [{ content: "", styles: { minCellHeight: PHOTO_ROW_HEIGHT + 4 } }],
        [
          {
            content: `< ${formatPhotoCaption(entry)} >`,
            styles: {
              halign: "center" as const,
              fontStyle: "bold" as const,
              fontSize: FONT_SIZE.caption,
              minCellHeight: PHOTO_CAPTION_HEIGHT,
            },
          },
        ],
      ],
      columnStyles: { 0: { cellWidth: CONTENT_WIDTH } },
      didDrawCell: (data: AutoTableCellHookData) => {
        // 이미지 행(0번)에만 그린다 — 캡션 행은 autoTable이 text로 알아서 그린다.
        if (data.section !== "body" || data.row.index !== 0) return;
        const image = entry;
        if (!image) return;
        const padding = 2;
        const imageX = data.cell.x + padding;
        const imageY = data.cell.y + padding;
        const imageW = data.cell.width - padding * 2;
        const imageH = data.cell.height - padding * 2;
        doc.addImage(
          image.dataUrl,
          image.format,
          imageX,
          imageY,
          imageW,
          imageH,
        );

        // 탐지 하자 박스(#1333). addImage 는 이미지를 위 사각형에 그대로 늘려 넣으므로
        // 정규화 좌표(0~1)가 이 사각형에 선형 대응한다 — object-cover 같은 크롭 보정이 필요 없다.
        const boxes = image.boxes ?? [];
        if (boxes.length === 0) return;
        doc.setDrawColor(...BOX_COLOR);
        doc.setLineWidth(LINE_OUTER);
        boxes.forEach((box) => {
          doc.rect(
            imageX + box.x * imageW,
            imageY + box.y * imageH,
            box.width * imageW,
            box.height * imageH,
          );
        });
        // 표 괘선이 이 색·굵기를 물려받지 않도록 원복한다(autoTable 이 셀마다 다시 그린다).
        doc.setDrawColor(...BLACK);
        doc.setLineWidth(LINE_INNER);
      },
    });
  };

  // ── 위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도(수동 섹션) ─────────────────────────
  // 원본에서 이 섹션은 텍스트가 아니라 이미지 자체가 본문이다(위치 지도, 전경 사진, 도면 스캔본).
  // 편집기가 업로드 시점에 이미 리사이즈된 JPEG data URL로 저장해 두므로(resizeImageToDataUrl),
  // 부위별 사진과 달리 fetch로 불러올 필요 없이 바로 그린다 — 페이지 경계 처리는 부위별 사진과
  // 동일한 "사진 1장 = 표 1개, 통째로 들어갈 자리 확인 후 그리기" 패턴을 그대로 재사용한다(#1409).
  const renderLocationDrawingPhotoTable = (
    image: LocationDrawingPhotoItem,
    startY: number,
  ): void => {
    autoTable(doc, {
      ...tableDefaults,
      startY,
      tableWidth: CONTENT_WIDTH,
      body: [
        [{ content: "", styles: { minCellHeight: PHOTO_ROW_HEIGHT + 4 } }],
        [
          {
            content: `< ${image.caption.trim() || "이미지"} >`,
            styles: {
              halign: "center" as const,
              fontStyle: "bold" as const,
              fontSize: FONT_SIZE.caption,
              minCellHeight: PHOTO_CAPTION_HEIGHT,
            },
          },
        ],
      ],
      columnStyles: { 0: { cellWidth: CONTENT_WIDTH } },
      didDrawCell: (data: AutoTableCellHookData) => {
        if (data.section !== "body" || data.row.index !== 0) return;
        const padding = 2;
        doc.addImage(
          image.dataUrl,
          "JPEG",
          data.cell.x + padding,
          data.cell.y + padding,
          data.cell.width - padding * 2,
          data.cell.height - padding * 2,
        );
      },
    });
  };

  const renderLocationDrawingPhotosBlock = (
    label: string,
    data: LocationDrawingPhotosSectionData,
    startY: number,
  ): number => {
    if (data.images.length === 0) {
      // 다른 수동 섹션과 동일하게, 비어 있어도 섹션 자체는 생략하지 않고 상태를 표시한다
      // (부위별 사진과 달리 이 섹션은 순서에 사용자가 직접 추가한 항목이라 자동 생략 대상이 아님).
      const y = blockTitle(label, startY, false);
      autoTable(doc, {
        ...tableDefaults,
        startY: y,
        body: [["추가된 이미지가 없습니다."]],
        bodyStyles: { minCellHeight: 24, valign: "top", halign: "left" },
      });
      return lastTableY();
    }
    let y = sectionTitle(
      label,
      ensureSpace(startY, SECTION_TITLE_HEIGHT + PHOTO_BLOCK_HEIGHT),
    );
    data.images.forEach((image) => {
      y = ensureSpace(y, PHOTO_BLOCK_HEIGHT);
      renderLocationDrawingPhotoTable(image, y);
      y = lastTableY();
    });
    return y;
  };

  // ── 편집기 순서(sectionOrder)대로 렌더링 ────────────────────────────────
  // 원본처럼 여러 소절을 한 페이지에 채운다: 섹션 사이 간격만큼 남는 공간이 있으면 이어 쓰고,
  // 최소 여백(제목+한 줄 표 분량)조차 없을 때만 새 페이지로 넘긴다. 제출문은 formal한 커버
  // 페이지 관용구라 예외 — 항상 전/후로 새 페이지를 강제한다(BLOCK_GAP/MIN_BLOCK_SPACE 값은
  // 시행착오로 정한 여유값이라 실측 근거는 없다).
  const BLOCK_GAP = 10;
  const MIN_BLOCK_SPACE = 40;
  const manualSections = content.manualSections ?? [];
  // 사진이 0장이면 'photos'를 순서에서 아예 뺀다 — 다른 빈 섹션들과 달리 사진은 "표시할 값이
  // 없다"는 플레이스홀더조차 원본 관용구에 없어(원래 사진이 없으면 전경사진 페이지 자체가
  // 없다), 넣어두면 번호만 차지하는 빈 자리가 생긴다.
  const order = resolveSectionOrder(content).filter(
    (key) => key !== "photos" || photoEntries.length > 0,
  );
  let cursorY = MARGIN_X;
  // 절 번호는 "번호를 받는 섹션"만 센다 — 제출문은 원본에서도 번호 없는 커버 페이지라
  // 1번을 잡아먹으면 뒤 번호가 통째로 밀린다. 결과 요약 뒤에 오는 진단 외관조사결과·상태평가·
  // 안전성평가·현장시험은 절 번호 대신 `가./나./다./라.` 소절 번호를 이어 받는다.
  let sectionNumber = 0;
  let summarySubsectionIndex = 0;
  let summaryRendered = false;

  order.forEach((key, index) => {
    const manual = !isFixedSectionKey(key)
      ? manualSections.find((section) => section.id === key)
      : undefined;
    const isSubmission = manual?.type === "submission";
    const nested =
      summaryRendered &&
      SUMMARY_SUBSECTION_TYPES.has(isFixedSectionKey(key) ? key : (manual?.type ?? ""));
    const marker = isSubmission
      ? ""
      : nested
        ? `${KOREAN_ORDINALS[summarySubsectionIndex++] ?? "기타"}.`
        : `${++sectionNumber}.`;
    if (key === "summary") summaryRendered = true;

    if (index === 0) {
      cursorY = MARGIN_X;
    } else if (isSubmission) {
      doc.addPage();
      cursorY = MARGIN_X;
    } else {
      cursorY = ensureSpace(cursorY + BLOCK_GAP, MIN_BLOCK_SPACE);
    }

    if (isFixedSectionKey(key)) {
      if (key === "overview")
        cursorY = renderOverviewBlock(`${marker} 기본현황`, cursorY);
      else if (key === "summary")
        cursorY = renderSummaryBlock(`${marker} 결과 요약`, cursorY);
      else if (key === "detail")
        cursorY = renderDetailBlock(
          `${marker} 진단 외관조사결과 기본사항`,
          cursorY,
          nested,
        );
      else if (key === "recommendation")
        cursorY = renderRecommendationBlock(
          `${marker} 보수ㆍ보강(안)`,
          cursorY,
          nested,
        );
      else cursorY = renderPhotosBlock(`${marker} 부위별 사진`, cursorY);
      return;
    }

    if (!manual) return;
    if (manual.type === "submission") {
      renderSubmissionBlock(manual.data as SubmissionSectionData, cursorY);
      // 다음 섹션은 반드시 새 페이지에서 시작 — 커서를 페이지 하단 너머로 밀어 다음 반복의
      // ensureSpace가 무조건 addPage하도록 유도한다.
      cursorY = BOTTOM_LIMIT + 1;
    } else if (manual.type === "participants") {
      cursorY = renderParticipantsBlock(
        `${marker} 참여기술진 명단`,
        manual.data as ParticipantsSectionData,
        cursorY,
      );
    } else if (manual.type === "location-drawing-photos") {
      cursorY = renderLocationDrawingPhotosBlock(
        `${marker} ${manual.title}`,
        manual.data as LocationDrawingPhotosSectionData,
        cursorY,
      );
    } else {
      cursorY = renderGenericManualBlock(
        `${marker} ${manual.title}`,
        manual.data as GenericManualSectionData,
        cursorY,
        nested,
      );
    }
  });

  return doc.output("blob");
}
