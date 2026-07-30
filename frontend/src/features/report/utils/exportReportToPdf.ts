import notoBoldUrl from '../../../assets/fonts/NotoSansKR-Bold.subset.ttf?url';
import notoRegularUrl from '../../../assets/fonts/NotoSansKR-Regular.subset.ttf?url';
import type {
  GenericManualSectionData,
  ParticipantsSectionData,
  ReportContent,
  SubmissionSectionData,
} from '../types';
import { isFixedSectionKey, resolveSectionOrder } from './sectionOrder';

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

const FONT_NAME = 'NotoSansKR';
const REGULAR_FONT_FILE = 'NotoSansKR-Regular.ttf';
const BOLD_FONT_FILE = 'NotoSansKR-Bold.ttf';

const PAGE_WIDTH = 210;
const PAGE_HEIGHT = 297;
const MARGIN_X = 23;
const CONTENT_WIDTH = PAGE_WIDTH - MARGIN_X * 2;
/** 소절(`가.`, `나.`) 아래 딸린 표는 원본에서 본문보다 약 3.5mm 들여쓴다. */
const SUB_TABLE_INDENT = 3.5;
const BOTTOM_LIMIT = PAGE_HEIGHT - MARGIN_X;
/** 부위별 사진 표에서 사진 1장이 차지하는 셀 높이(원본 실측 96mm). */
const PHOTO_ROW_HEIGHT = 96;

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

const PT_TO_MM = 25.4 / 72;
/** 표 내부 괘선 0.36pt. */
const LINE_INNER = 0.36 * PT_TO_MM;
/** 표 외곽 테두리 1.08pt. */
const LINE_OUTER = 1.08 * PT_TO_MM;

export interface ReportPdfContext {
  facilityName?: string;
  inspectionRound?: number;
  issuedAt?: Date;
  defectImages?: ReportPdfImage[];
}

export interface ReportPdfImage {
  defectType: string;
  imageUrl: string;
  /** 하자 등급(A~E) — 캡션에 "유형(등급)"으로 함께 표기해 사진만 보고도 심각도를 알 수 있게 한다. */
  grade?: string;
  /** AI/점검자 분석 요약 — 캡션에 짧게 덧붙여 "균열" 같은 유형명 단독 표기를 피한다. */
  summary?: string;
}

const PHOTO_CAPTION_SUMMARY_MAX = 40;

/**
 * 사진 캡션은 유형명만 단독으로 쓰지 않는다 — "균열"만으로는 어느 사진인지 구별이 안 된다.
 * grade·summary는 같은 하자 레코드(Defect)에서 thumbnailUrl과 함께 나온 값이라(별도 매칭 없이
 * 그대로 짝지어 넘어옴) 오표기 위험 없이 안전하게 붙일 수 있다.
 */
function formatPhotoCaption(image: ReportPdfImage): string {
  const type = image.defectType || '부위';
  const gradeSuffix = image.grade ? `(${image.grade}등급)` : '';
  const summary = (image.summary ?? '').trim();
  if (!summary) return `${type}${gradeSuffix}`;
  const truncated =
    summary.length > PHOTO_CAPTION_SUMMARY_MAX ? `${summary.slice(0, PHOTO_CAPTION_SUMMARY_MAX)}…` : summary;
  return `${type}${gradeSuffix} — ${truncated}`;
}

/**
 * jspdf-autotable의 CellHookData 중 실제로 쓰는 필드만 뽑은 최소 타입 — jspdf-autotable을
 * 동적 import(`await import('jspdf-autotable')`)로 쓰고 있어 정적 타입을 끌어오지 않는다.
 * 사진 표에서 셀이 실제로 어느 페이지·좌표에 그려졌는지(자동 페이지분할 이후 값)를 읽어
 * 그 자리에 이미지를 겹쳐 그리는 용도다.
 */
interface AutoTableCellHookData {
  section: 'head' | 'body' | 'foot';
  row: { index: number };
  cell: { x: number; y: number; width: number; height: number };
}

async function toBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = reader.result as string;
      resolve(result.slice(result.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function formatDate(date: Date): string {
  return `${date.getFullYear()}년 ${String(date.getMonth() + 1).padStart(2, '0')}월 ${String(date.getDate()).padStart(2, '0')}일`;
}

function formatOptionalDate(date?: Date): string {
  return date ? formatDate(date) : '-';
}

function normalizeGradeCount(countByGrade: Record<string, number>, grade: string): string {
  return String(countByGrade[grade] ?? 0);
}

/**
 * 부재별 상태평가 등급은 표준서식에서 **소문자** a~e로 적는다(대문자 A~E는 시설물 종합
 * 안전등급 전용). `defects.grade` enum 은 대문자로 저장되므로 표시 계층에서만 변환한다 —
 * 저장 값을 바꾸는 것이 아니다.
 */
function toMemberGrade(grade: string): string {
  const normalized = grade.trim();
  return /^[A-Ea-e]$/.test(normalized) ? normalized.toLowerCase() : normalized || '-';
}

/** 등급별 건수에서 최악(가장 심각) 등급을 뽑아 "상태평가 결과"로 쓴다. E가 가장 심각. */
function worstGrade(countByGrade: Record<string, number>): string {
  const worst = (['E', 'D', 'C', 'B', 'A'] as const).find((grade) => (countByGrade[grade] ?? 0) > 0);
  return worst ? worst.toLowerCase() : '-';
}

function legalBasisLabel(legalBasis: string, verified: boolean): string {
  const basis = legalBasis || '관련 근거 없음';
  return verified ? basis : `${basis} (미검증)`;
}

/** 셀 안의 목록은 원본처럼 `ㆍ` 불릿을 붙여 줄바꿈으로 나열한다(번호 없음). */
function toBulletCell(values: string[], fallback: string): string {
  return values.length > 0 ? values.map((value) => `ㆍ${value}`).join('\n') : fallback;
}

async function loadPdfImage(imageUrl: string): Promise<{ dataUrl: string; format: 'JPEG' | 'PNG' } | null> {
  try {
    const response = await fetch(imageUrl, { credentials: 'include' });
    if (!response.ok) return null;
    const blob = await response.blob();
    const format = blob.type === 'image/png' ? 'PNG' : blob.type === 'image/jpeg' ? 'JPEG' : null;
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

export function buildReportPdfFileName(inspectionId: number): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `점검보고서_${inspectionId}_${yyyy}${mm}${dd}.pdf`;
}

// 표준서식의 구성(제출문 → 결과표 → 결과 요약 → 외관조사결과 → 보수ㆍ보강방안 → 참여기술진 명단
// → 부위별 사진)을 따르되, 실제 순서는 편집기에서 사용자가 정한 sectionOrder를 그대로 따른다.
// 안전성평가(SF)·현장시험·위치도처럼 대응 데이터가 아예 없는 원본 섹션은 빈 표를 만들지 않고
// 제외한다 — 점검관리 플로우가 수집하지 않는 값이라 채울 방법이 없다.
export async function exportReportToPdf(
  content: ReportContent,
  context: ReportPdfContext = {},
): Promise<Blob> {
  const [{ default: jsPDF }, { default: autoTable }, regularFontResponse, boldFontResponse] = await Promise.all([
    import('jspdf'),
    import('jspdf-autotable'),
    fetch(notoRegularUrl),
    fetch(notoBoldUrl),
  ]);
  const [regularFontBase64, boldFontBase64] = await Promise.all([
    toBase64(await regularFontResponse.blob()),
    toBase64(await boldFontResponse.blob()),
  ]);

  const doc = new jsPDF({ unit: 'mm', format: 'a4' });
  doc.addFileToVFS(REGULAR_FONT_FILE, regularFontBase64);
  doc.addFont(REGULAR_FONT_FILE, FONT_NAME, 'normal');
  doc.addFileToVFS(BOLD_FONT_FILE, boldFontBase64);
  doc.addFont(BOLD_FONT_FILE, FONT_NAME, 'bold');
  doc.setFont(FONT_NAME, 'normal');
  doc.setTextColor(...BLACK);
  doc.setDrawColor(...BLACK);
  // 원본은 줄간격이 좁다(10pt 본문에 행높이 약 17pt).
  doc.setLineHeightFactor(1.35);

  const facilityName = context.facilityName || content.overview.facility_summary || '시설물명 미기재';
  const inspectionLabel = context.inspectionRound ? `제${context.inspectionRound}회차` : '-';
  const loadedDefectImages = await Promise.all(
    (context.defectImages ?? []).map(async (image) => {
      const loaded = await loadPdfImage(image.imageUrl);
      return loaded ? { ...image, ...loaded } : null;
    }),
  );
  // 사진은 점검 API가 준 축소본만 쓴다(새로 만들지 않음). 렌더 순서(sectionOrder)와 무관하게
  // 필요하므로 다른 섹션 render 함수들보다 먼저 확정해 둔다.
  const photoEntries = loadedDefectImages.filter(
    (image): image is ReportPdfImage & { dataUrl: string; format: 'JPEG' | 'PNG' } => image !== null,
  );

  const tableDefaults = {
    theme: 'grid' as const,
    margin: { left: MARGIN_X, right: MARGIN_X, top: MARGIN_X, bottom: MARGIN_X },
    rowPageBreak: 'avoid' as const,
    showHead: 'everyPage' as const,
    // 외곽 테두리를 내부 괘선의 3배로 — 관공서 표의 인상을 만드는 핵심 대비.
    tableLineColor: BLACK,
    tableLineWidth: LINE_OUTER,
    styles: {
      font: FONT_NAME,
      fontStyle: 'normal' as const,
      fontSize: FONT_SIZE.table,
      cellPadding: { top: 1.3, right: 1.6, bottom: 1.3, left: 1.6 },
      lineColor: BLACK,
      lineWidth: LINE_INNER,
      textColor: BLACK,
      valign: 'middle' as const,
      overflow: 'linebreak' as const,
    },
    headStyles: {
      font: FONT_NAME,
      fontStyle: 'bold' as const,
      fillColor: HEAD_FILL,
      textColor: BLACK,
      halign: 'center' as const,
      lineWidth: LINE_INNER,
    },
  };

  /** 회색 배경 + Bold 중앙정렬 라벨열 스타일(원본의 좌측 구분열). */
  const labelColumn = (cellWidth: number) => ({
    cellWidth,
    fontStyle: 'bold' as const,
    fillColor: HEAD_FILL,
    halign: 'center' as const,
  });

  /** 소절 표는 본문보다 들여쓰고 그만큼 폭을 줄인다. */
  const subTable = {
    margin: { left: MARGIN_X + SUB_TABLE_INDENT, right: MARGIN_X, top: MARGIN_X, bottom: MARGIN_X },
    tableWidth: CONTENT_WIDTH - SUB_TABLE_INDENT,
  };

  const lastTableY = () => (doc as typeof doc & { lastAutoTable: { finalY: number } }).lastAutoTable.finalY;

  /** 절 제목 — `1. 기본현황` Bold 14pt. 원본에는 하단 구분선이 없다. */
  const sectionTitle = (label: string, y: number) => {
    doc.setFont(FONT_NAME, 'bold');
    doc.setFontSize(FONT_SIZE.sectionTitle);
    doc.text(label, MARGIN_X, y);
    doc.setFont(FONT_NAME, 'normal');
    return y + 6;
  };

  /** 소절 제목 — `가. 일반현황` Bold 11pt. */
  const subsectionTitle = (label: string, y: number) => {
    doc.setFont(FONT_NAME, 'bold');
    doc.setFontSize(FONT_SIZE.subsectionTitle);
    doc.text(label, MARGIN_X + SUB_TABLE_INDENT, y);
    doc.setFont(FONT_NAME, 'normal');
    return y + 4.2;
  };

  /** 남은 지면이 부족하면 새 페이지로 넘긴다(소절 제목이 페이지 끝에 홀로 남는 것 방지). */
  const ensureSpace = (y: number, needed: number) => {
    if (y + needed <= BOTTOM_LIMIT) return y;
    doc.addPage();
    return MARGIN_X;
  };

  // ── 1. 기본현황 ──────────────────────────────────────────────────────────
  const renderOverviewBlock = (label: string, startY: number): number => {
    let y = sectionTitle(label, startY);
    y = subsectionTitle('가. 일반현황', y);

    // 원본의 2단 라벨-값 표(`구 분 | 내 용 | 구 분 | 내 용`) — 지면을 절약하는 서식 관용구.
    // 라벨 텍스트에 넣은 공백은 원본이 글자수를 맞추는 방식이라 그대로 따른다.
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      body: [
        ['시 설 물 명', facilityName, '점검 회차', inspectionLabel],
        ['작성 기준일', formatOptionalDate(context.issuedAt), '확인 결함', `${content.summary.total_count}건`],
      ],
      columnStyles: {
        0: labelColumn(28),
        1: { cellWidth: 55 },
        2: labelColumn(24),
        3: { cellWidth: 'auto' },
      },
    });

    y = subsectionTitle('나. 점검 개요', lastTableY() + 6);
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      body: [
        ['점검 목적', content.overview.purpose || '-'],
        ['시설물 개요', content.overview.facility_summary || '-'],
        ['점검 범위', content.overview.scope || '-'],
      ],
      columnStyles: { 0: labelColumn(28), 1: { cellWidth: 'auto' } },
    });
    return lastTableY();
  };

  // ── 2. 결과 요약 ─────────────────────────────────────────────────────────
  const renderSummaryBlock = (label: string, startY: number): number => {
    let y = sectionTitle(label, startY);
    y = subsectionTitle('가. 책임기술자 종합의견', y);
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      body: [[content.summary.overall_opinion || '종합의견이 작성되지 않았습니다.']],
      bodyStyles: { minCellHeight: 40, valign: 'top', halign: 'left' },
    });

    y = subsectionTitle('나. 결함 등급별 현황', ensureSpace(lastTableY() + 6, 30));
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      head: [['구  분', 'a', 'b', 'c', 'd', 'e', '합  계']],
      body: [
        [
          '건  수',
          normalizeGradeCount(content.summary.count_by_grade, 'A'),
          normalizeGradeCount(content.summary.count_by_grade, 'B'),
          normalizeGradeCount(content.summary.count_by_grade, 'C'),
          normalizeGradeCount(content.summary.count_by_grade, 'D'),
          normalizeGradeCount(content.summary.count_by_grade, 'E'),
          String(content.summary.total_count),
        ],
      ],
      styles: { ...tableDefaults.styles, halign: 'center' },
      columnStyles: { 0: labelColumn(28), 6: { fontStyle: 'bold' } },
    });

    y = subsectionTitle('다. 주요 발견사항', ensureSpace(lastTableY() + 6, 30));
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      body: [[toBulletCell(content.summary.key_findings, '주요 발견사항이 없습니다.')]],
      bodyStyles: { valign: 'top', halign: 'left' },
    });
    return lastTableY();
  };

  // ── 3. 진단 외관조사결과 ─────────────────────────────────────────────────
  const renderDetailBlock = (label: string, startY: number): number => {
    const y = sectionTitle(label, startY);
    // 원본은 표 위에 "상태평가 결과 : b" 를 회색 배경 한 행으로 얹는다. 등급별 건수에서
    // 최악 등급을 뽑아 같은 자리에 채운다(새 데이터 요구 없음).
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      body: [['상태평가 결과 및 보수ㆍ보강', `상태평가 결과 : ${worstGrade(content.summary.count_by_grade)}`]],
      bodyStyles: { fillColor: HEAD_FILL, fontStyle: 'bold', halign: 'center' },
      columnStyles: { 0: { cellWidth: 96 }, 1: { cellWidth: 'auto' } },
    });

    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: lastTableY(),
      head: [['연번', '결함발생 부재', '상태\n평가', '결함종류', '조사 결과', '추정 원인']],
      body:
        content.detail.items.length > 0
          ? content.detail.items.map((item, index) => [
              String(index + 1),
              item.location || '-',
              toMemberGrade(item.severity_grade),
              item.defect_type || '-',
              item.description || '-',
              item.cause || '-',
            ])
          : [['-', '-', '-', '-', '확인된 결함이 없습니다.', '-']],
      // 좁은 열은 원본처럼 9pt로 강등한다.
      columnStyles: {
        0: { cellWidth: 11, halign: 'center', fontSize: FONT_SIZE.tableNarrow },
        1: { cellWidth: 26 },
        2: { cellWidth: 12, halign: 'center' },
        3: { cellWidth: 24 },
        4: { cellWidth: 'auto' },
        5: { cellWidth: 38 },
      },
    });
    return lastTableY();
  };

  // ── 4. 보수ㆍ보강방안 ────────────────────────────────────────────────────
  const renderRecommendationBlock = (label: string, startY: number): number => {
    let y = sectionTitle(label, startY);
    y = subsectionTitle('가. 보수ㆍ보강(안)', y);
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      head: [['연번', '대상 부위', '보수ㆍ보강(안)', '조치\n우선순위', '적용 근거']],
      body:
        content.recommendation.items.length > 0
          ? content.recommendation.items.map((item, index) => [
              String(index + 1),
              item.target || '-',
              item.method || '-',
              item.priority || '-',
              legalBasisLabel(item.legal_basis, item.legal_basis_verified),
            ])
          : [['-', '-', '권고 조치가 없습니다.', '-', '-']],
      columnStyles: {
        0: { cellWidth: 11, halign: 'center', fontSize: FONT_SIZE.tableNarrow },
        1: { cellWidth: 30 },
        2: { cellWidth: 'auto' },
        3: { cellWidth: 18, halign: 'center', fontSize: FONT_SIZE.tableNarrow },
        4: { cellWidth: 44 },
      },
    });

    y = subsectionTitle('나. 지속 관찰 부위', ensureSpace(lastTableY() + 6, 30));
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      body: [[toBulletCell(content.recommendation.monitoring_points, '지속 관찰이 필요한 부위가 없습니다.')]],
      bodyStyles: { valign: 'top', halign: 'left' },
    });
    return lastTableY();
  };

  // ── 제출문(수동 섹션) ────────────────────────────────────────────────────
  // 계약 당사자·수신 기관명은 하자점검 도메인 밖 값이라 백엔드가 생산할 수 없다 — 편집기에서
  // 사용자가 직접 입력한 값을 그대로 배치한다. 원본 관용구: 24pt Bold 중앙 제목, 수신자
  // 좌측 상단, 본문 좌측, 발신 정보는 우측 정렬. 늘 단독 페이지(커버 페이지 관용구)이므로
  // startY는 항상 페이지 시작점(MARGIN_X)이다.
  const renderSubmissionBlock = (data: SubmissionSectionData, startY: number): number => {
    doc.setFont(FONT_NAME, 'bold');
    doc.setFontSize(FONT_SIZE.submissionTitle);
    doc.text('제  출  문', PAGE_WIDTH / 2, startY + 6, { align: 'center' });
    doc.setFont(FONT_NAME, 'normal');

    let y = startY + 28;
    doc.setFont(FONT_NAME, 'bold');
    doc.setFontSize(FONT_SIZE.recipient);
    doc.text(data.recipient || '수신자 미기재', MARGIN_X, y);
    doc.setFont(FONT_NAME, 'normal');

    y += 14;
    doc.setFontSize(FONT_SIZE.body);
    const paragraph =
      `귀 기관과 ${data.contractDate || '-'} 계약 체결한 "${facilityName} 정밀안전점검"에 대한 ` +
      '결과를 본 보고서에 수록하여 제출합니다.';
    const wrapped = doc.splitTextToSize(paragraph, CONTENT_WIDTH) as string[];
    doc.text(wrapped, MARGIN_X, y);
    y += wrapped.length * 6.5 + 50;

    doc.setFontSize(FONT_SIZE.table + 2);
    doc.text(formatOptionalDate(context.issuedAt), PAGE_WIDTH - MARGIN_X, y, { align: 'right' });

    y += 16;
    doc.text(data.companyAddress || '-', PAGE_WIDTH - MARGIN_X, y, { align: 'right' });

    y += 8;
    doc.setFont(FONT_NAME, 'bold');
    doc.text(data.companyName || '-', PAGE_WIDTH - MARGIN_X, y, { align: 'right' });

    y += 8;
    doc.text(`대표자  ${data.representativeName || '-'} (인)`, PAGE_WIDTH - MARGIN_X, y, { align: 'right' });
    doc.setFont(FONT_NAME, 'normal');
    return y;
  };

  // ── 참여기술진 명단(수동 섹션) ───────────────────────────────────────────
  // 참여자 실명·자격은 마찬가지로 도메인 밖 값. 원본은 표 형태(구분/성명/자격 및 주요경력/기간).
  const renderParticipantsBlock = (label: string, data: ParticipantsSectionData, startY: number): number => {
    const y = sectionTitle(label, startY);
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      head: [['구  분', '성  명', '자격 및 주요경력', '과업 참여기간']],
      body:
        data.entries.length > 0
          ? data.entries.map((entry) => [
              entry.role || '-',
              entry.name || '-',
              entry.qualification || '-',
              entry.period || '-',
            ])
          : [['-', '-', '참여기술진 정보가 없습니다.', '-']],
      columnStyles: {
        0: { cellWidth: 32, halign: 'center' },
        1: { cellWidth: 28, halign: 'center' },
        2: { cellWidth: 'auto' },
        3: { cellWidth: 48, halign: 'center' },
      },
    });
    return lastTableY();
  };

  // ── 일반 수동 섹션 ──────────────────────────────────────────────────────
  // 안전성 평가·현장시험·도면류처럼 DB/AI 스키마에 없는 항목은 사용자가 입력한 본문을 같은
  // 관공서 표 양식으로 싣는다. 새 컬럼 없이 reports.content_json 안에서만 왕복된다.
  const renderGenericManualBlock = (label: string, data: GenericManualSectionData, startY: number): number => {
    const y = sectionTitle(label, startY);
    autoTable(doc, {
      ...tableDefaults,
      ...subTable,
      startY: y,
      body: [[data.body?.trim() || '입력된 내용이 없습니다.']],
      bodyStyles: { minCellHeight: 24, valign: 'top', halign: 'left' },
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
    const y = sectionTitle(label, startY);
    autoTable(doc, {
      ...tableDefaults,
      startY: y,
      tableWidth: CONTENT_WIDTH,
      body: photoEntries.flatMap((image) => [
        [{ content: '', styles: { minCellHeight: PHOTO_ROW_HEIGHT + 4 } }],
        [
          {
            content: `< ${formatPhotoCaption(image)} >`,
            styles: { halign: 'center' as const, fontStyle: 'bold' as const, fontSize: FONT_SIZE.caption, minCellHeight: 9 },
          },
        ],
      ]),
      columnStyles: { 0: { cellWidth: CONTENT_WIDTH } },
      didDrawCell: (data: AutoTableCellHookData) => {
        // 이미지 행(짝수 인덱스)에만 그린다 — 캡션 행은 autoTable이 text로 알아서 그린다.
        if (data.section !== 'body' || data.row.index % 2 !== 0) return;
        const image = photoEntries[data.row.index / 2];
        if (!image) return;
        const padding = 2;
        doc.addImage(
          image.dataUrl,
          image.format,
          data.cell.x + padding,
          data.cell.y + padding,
          data.cell.width - padding * 2,
          data.cell.height - padding * 2,
        );
      },
    });
    return lastTableY();
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
  const order = resolveSectionOrder(content).filter((key) => key !== 'photos' || photoEntries.length > 0);
  let cursorY = MARGIN_X;

  order.forEach((key, index) => {
    const manual = !isFixedSectionKey(key) ? manualSections.find((section) => section.id === key) : undefined;
    const isSubmission = manual?.type === 'submission';
    const number = index + 1;

    if (index === 0) {
      cursorY = MARGIN_X;
    } else if (isSubmission) {
      doc.addPage();
      cursorY = MARGIN_X;
    } else {
      cursorY = ensureSpace(cursorY + BLOCK_GAP, MIN_BLOCK_SPACE);
    }

    if (isFixedSectionKey(key)) {
      if (key === 'overview') cursorY = renderOverviewBlock(`${number}. 기본현황`, cursorY);
      else if (key === 'summary') cursorY = renderSummaryBlock(`${number}. 결과 요약`, cursorY);
      else if (key === 'detail') cursorY = renderDetailBlock(`${number}. 진단 외관조사결과`, cursorY);
      else if (key === 'recommendation') cursorY = renderRecommendationBlock(`${number}. 보수ㆍ보강방안`, cursorY);
      else cursorY = renderPhotosBlock(`${number}. 부위별 사진`, cursorY);
      return;
    }

    if (!manual) return;
    if (manual.type === 'submission') {
      renderSubmissionBlock(manual.data as SubmissionSectionData, cursorY);
      // 다음 섹션은 반드시 새 페이지에서 시작 — 커서를 페이지 하단 너머로 밀어 다음 반복의
      // ensureSpace가 무조건 addPage하도록 유도한다.
      cursorY = BOTTOM_LIMIT + 1;
    } else if (manual.type === 'participants') {
      cursorY = renderParticipantsBlock(`${number}. 참여기술진 명단`, manual.data as ParticipantsSectionData, cursorY);
    } else {
      cursorY = renderGenericManualBlock(`${number}. ${manual.title}`, manual.data as GenericManualSectionData, cursorY);
    }
  });

  return doc.output('blob');
}
