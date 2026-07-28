import pretendardBoldUrl from 'pretendard/dist/public/static/alternative/Pretendard-Bold.ttf?url';
import pretendardRegularUrl from 'pretendard/dist/public/static/alternative/Pretendard-Regular.ttf?url';
import type { ReportContent } from '../types';

const FONT_NAME = 'Pretendard';
const REGULAR_FONT_FILE = 'Pretendard-Regular.ttf';
const BOLD_FONT_FILE = 'Pretendard-Bold.ttf';
const PAGE_MARGIN_X = 18;
const PAGE_MARGIN_Y = 18;
const PAGE_WIDTH = 210;
const PAGE_HEIGHT = 297;
const CONTENT_WIDTH = PAGE_WIDTH - PAGE_MARGIN_X * 2;
const DOCUMENT_TITLE = '정밀안전점검 보고서';
const DOCUMENT_SUBTITLE = '시설물 안전점검 결과';
const TABLE_LINE_COLOR: [number, number, number] = [88, 96, 105];
const TABLE_HEAD_FILL: [number, number, number] = [238, 241, 244];
const TABLE_LABEL_FILL: [number, number, number] = [247, 248, 249];
const TEXT_COLOR: [number, number, number] = [32, 36, 40];

export interface ReportPdfContext {
  facilityName?: string;
  inspectionRound?: number;
  issuedAt?: Date;
  defectImages?: ReportPdfImage[];
}

export interface ReportPdfImage {
  defectType: string;
  imageUrl: string;
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
  return `${date.getFullYear()}. ${String(date.getMonth() + 1).padStart(2, '0')}. ${String(date.getDate()).padStart(2, '0')}.`;
}

function formatOptionalDate(date?: Date): string {
  return date ? formatDate(date) : '-';
}

function normalizeGradeCount(countByGrade: Record<string, number>, grade: string): string {
  return String(countByGrade[grade] ?? 0);
}

function legalBasisLabel(legalBasis: string, verified: boolean): string {
  const basis = legalBasis || '-';
  return verified ? basis : `${basis} (미검증)`;
}

async function loadPdfImage(imageUrl: string): Promise<{ dataUrl: string; format: 'JPEG' | 'PNG' } | null> {
  try {
    const response = await fetch(imageUrl, { credentials: 'include' });
    if (!response.ok) return null;
    const blob = await response.blob();
    const format = blob.type === 'image/png' ? 'PNG' : blob.type === 'image/jpeg' ? 'JPEG' : null;
    if (!format) return null;
    return { dataUrl: await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => resolve(reader.result as string);
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(blob);
    }), format };
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

// 표준서식의 구성(표지 -> 결과표 -> 실시결과 요약문·부위별 사진 -> 보수·보강방안 -> 종합결론 및 건의)을 유지한다.
// 점검자처럼 계약에 없는 값은 만들지 않으며, 사진은 점검 API가 제공한 축소본만 사용한다.
export async function exportReportToPdf(
  content: ReportContent,
  context: ReportPdfContext = {},
): Promise<Blob> {
  const [{ default: jsPDF }, { default: autoTable }, regularFontResponse, boldFontResponse] = await Promise.all([
    import('jspdf'),
    import('jspdf-autotable'),
    fetch(pretendardRegularUrl),
    fetch(pretendardBoldUrl),
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
  doc.setLineHeightFactor(1.45);

  const facilityName = context.facilityName || content.overview.facility_summary || '시설물명 미기재';
  const inspectionLabel = context.inspectionRound ? `${context.inspectionRound}회차 안전점검` : '안전점검';
  const loadedDefectImages = await Promise.all(
    (context.defectImages ?? []).map(async (image) => {
      const loaded = await loadPdfImage(image.imageUrl);
      return loaded ? { ...image, ...loaded } : null;
    }),
  );
  const tableDefaults = {
    theme: 'grid' as const,
    margin: { left: PAGE_MARGIN_X, right: PAGE_MARGIN_X },
    rowPageBreak: 'avoid' as const,
    showHead: 'everyPage' as const,
    styles: {
      font: FONT_NAME,
      fontStyle: 'normal' as const,
      fontSize: 8.5,
      cellPadding: 2.4,
      lineColor: TABLE_LINE_COLOR,
      lineWidth: 0.16,
      textColor: TEXT_COLOR,
      valign: 'middle' as const,
    },
    headStyles: {
      font: FONT_NAME,
      fontStyle: 'bold' as const,
      fillColor: TABLE_HEAD_FILL,
      textColor: [20, 24, 28] as [number, number, number],
      halign: 'center' as const,
      lineWidth: 0.22,
    },
  };
  const sectionTitle = (number: number, title: string, y: number) => {
    doc.setFont(FONT_NAME, 'bold');
    doc.setFontSize(12.2);
    doc.setTextColor(20, 24, 28);
    doc.text(`${number}. ${title}`, PAGE_MARGIN_X, y);
    doc.setDrawColor(49, 57, 66);
    doc.setLineWidth(0.42);
    doc.line(PAGE_MARGIN_X, y + 2.5, PAGE_MARGIN_X + CONTENT_WIDTH, y + 2.5);
    doc.setFont(FONT_NAME, 'normal');
    doc.setTextColor(...TEXT_COLOR);
    return y + 8;
  };
  const lastTableY = () => (doc as typeof doc & { lastAutoTable: { finalY: number } }).lastAutoTable.finalY;

  // 표지에는 현재 계약으로 확인 가능한 시설물·회차·작성일만 배치한다.
  doc.setTextColor(...TEXT_COLOR);
  doc.setDrawColor(49, 57, 66);
  doc.setLineWidth(0.5);
  doc.rect(PAGE_MARGIN_X, PAGE_MARGIN_Y, CONTENT_WIDTH, PAGE_HEIGHT - PAGE_MARGIN_Y * 2);
  doc.setDrawColor(180, 186, 194);
  doc.setLineWidth(0.18);
  doc.line(PAGE_MARGIN_X + 10, 46, PAGE_MARGIN_X + CONTENT_WIDTH - 10, 46);
  doc.setFont(FONT_NAME, 'bold');
  doc.setFontSize(21);
  doc.text(DOCUMENT_TITLE, PAGE_WIDTH / 2, 96, { align: 'center' });
  doc.setFont(FONT_NAME, 'normal');
  doc.setFontSize(10.5);
  doc.text(DOCUMENT_SUBTITLE, PAGE_WIDTH / 2, 108, { align: 'center' });
  autoTable(doc, {
    ...tableDefaults,
    startY: 138,
    tableWidth: 116,
    margin: { left: 47, right: 47 },
    body: [
      ['시설물명', facilityName],
      ['점검 구분', inspectionLabel],
      ['작성 기준일', formatOptionalDate(context.issuedAt)],
    ],
    columnStyles: {
      0: { cellWidth: 32, fontStyle: 'bold', fillColor: TABLE_LABEL_FILL },
      1: { cellWidth: 84 },
    },
  });
  doc.addPage();
  let y = sectionTitle(1, '정밀안전점검 결과표', 25);
  autoTable(doc, {
    ...tableDefaults,
    startY: y,
    head: [['구분', '내용']],
    body: [
      ['점검 목적', content.overview.purpose || '-'],
      ['시설물 개요', content.overview.facility_summary || '-'],
      ['점검 범위', content.overview.scope || '-'],
      ['확인 결함 수', `${content.summary.total_count}건`],
    ],
    columnStyles: { 0: { cellWidth: 34, fontStyle: 'bold', fillColor: TABLE_LABEL_FILL }, 1: { cellWidth: 140 } },
  });
  y = lastTableY() + 12;
  doc.setFont(FONT_NAME, 'bold');
  doc.setFontSize(10);
  doc.text('결함 등급별 현황', PAGE_MARGIN_X, y);
  autoTable(doc, {
    ...tableDefaults,
    startY: y + 4,
    head: [['등급', 'A', 'B', 'C', 'D', 'E', '합계']],
    body: [[
      '건수',
      normalizeGradeCount(content.summary.count_by_grade, 'A'),
      normalizeGradeCount(content.summary.count_by_grade, 'B'),
      normalizeGradeCount(content.summary.count_by_grade, 'C'),
      normalizeGradeCount(content.summary.count_by_grade, 'D'),
      normalizeGradeCount(content.summary.count_by_grade, 'E'),
      String(content.summary.total_count),
    ]],
    styles: { ...tableDefaults.styles, halign: 'center' },
  });
  y = lastTableY() + 12;
  doc.setFont(FONT_NAME, 'bold');
  doc.setFontSize(10);
  doc.text('주요 발견사항', PAGE_MARGIN_X, y);
  autoTable(doc, {
    ...tableDefaults,
    startY: y + 4,
    head: [['번호', '발견사항']],
    body: content.summary.key_findings.length > 0
      ? content.summary.key_findings.map((finding, index) => [String(index + 1), finding])
      : [['-', '주요 발견사항이 없습니다.']],
    columnStyles: { 0: { cellWidth: 18, halign: 'center' }, 1: { cellWidth: 156 } },
  });

  doc.addPage();
  y = sectionTitle(2, '정밀안전점검 실시결과 요약문', 25);
  autoTable(doc, {
    ...tableDefaults,
    startY: y,
    head: [['번호', '결함 종류', '발생 위치', '등급', '조사 결과', '추정 원인']],
    body: content.detail.items.length > 0
      ? content.detail.items.map((item, index) => [
        String(index + 1), item.defect_type, item.location, item.severity_grade,
        item.description || '-', item.cause || '-',
      ])
      : [['-', '-', '-', '-', '확인된 결함이 없습니다.', '-']],
    columnStyles: {
      0: { cellWidth: 12, halign: 'center' },
      1: { cellWidth: 23 },
      2: { cellWidth: 28 },
      3: { cellWidth: 13, halign: 'center' },
      4: { cellWidth: 49 },
      5: { cellWidth: 49 },
    },
  });

  const photoEntries = loadedDefectImages.filter(
    (image): image is ReportPdfImage & { dataUrl: string; format: 'JPEG' | 'PNG' } => image !== null,
  );
  if (photoEntries.length > 0) {
    for (let index = 0; index < photoEntries.length; index += 2) {
      doc.addPage();
      y = sectionTitle(3, '대상시설물 부위별 사진', 25);
      const pageEntries = photoEntries.slice(index, index + 2);
      pageEntries.forEach((image, imageIndex) => {
        const imageY = y + imageIndex * 118;
        doc.setFont(FONT_NAME, 'bold');
        doc.setFontSize(9);
        doc.text(`${index + imageIndex + 1}. ${image.defectType}`, PAGE_MARGIN_X, imageY);
        doc.addImage(image.dataUrl, image.format, PAGE_MARGIN_X, imageY + 4, CONTENT_WIDTH, 96);
        doc.setFont(FONT_NAME, 'normal');
        doc.setFontSize(8);
        doc.text('점검 촬영 축소본', PAGE_MARGIN_X, imageY + 105);
      });
    }
  }

  doc.addPage();
  y = sectionTitle(4, '보수·보강방안', 25);
  autoTable(doc, {
    ...tableDefaults,
    startY: y,
    head: [['번호', '대상 부위', '권고 조치', '우선순위', '근거']],
    body: content.recommendation.items.length > 0
      ? content.recommendation.items.map((item, index) => [
        String(index + 1), item.target, item.method, item.priority,
        legalBasisLabel(item.legal_basis, item.legal_basis_verified),
      ])
      : [['-', '-', '권고 조치가 없습니다.', '-', '-']],
    columnStyles: {
      0: { cellWidth: 12, halign: 'center' },
      1: { cellWidth: 33 },
      2: { cellWidth: 55 },
      3: { cellWidth: 22, halign: 'center' },
      4: { cellWidth: 52 },
    },
  });
  y = lastTableY() + 12;
  doc.setFont(FONT_NAME, 'bold');
  doc.setFontSize(10);
  doc.text('주요 점검 대상 부위', PAGE_MARGIN_X, y);
  autoTable(doc, {
    ...tableDefaults,
    startY: y + 4,
    head: [['번호', '지속 관찰 사항']],
    body: content.recommendation.monitoring_points.length > 0
      ? content.recommendation.monitoring_points.map((point, index) => [String(index + 1), point])
      : [['-', '지속 관찰이 필요한 부위가 없습니다.']],
    columnStyles: { 0: { cellWidth: 18, halign: 'center' }, 1: { cellWidth: 156 } },
  });

  doc.addPage();
  y = sectionTitle(5, '종합결론 및 건의', 25);
  autoTable(doc, {
    ...tableDefaults,
    startY: y,
    head: [['종합결론 및 건의']],
    body: [[content.summary.overall_opinion || '종합결론 및 건의가 작성되지 않았습니다.']],
    styles: { ...tableDefaults.styles },
    bodyStyles: { minCellHeight: 52, valign: 'top' },
  });
  const totalPages = doc.getNumberOfPages();
  for (let page = 1; page <= totalPages; page += 1) {
    doc.setPage(page);
    if (page > 1) {
      doc.setDrawColor(174, 180, 188);
      doc.setLineWidth(0.18);
      doc.line(PAGE_MARGIN_X, 12, PAGE_MARGIN_X + CONTENT_WIDTH, 12);
      doc.setFont(FONT_NAME, 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(72, 80, 88);
      doc.text(DOCUMENT_TITLE, PAGE_MARGIN_X, 9);
    }
    doc.setFont(FONT_NAME, 'normal');
    doc.setFontSize(7.2);
    doc.setTextColor(92, 99, 108);
    doc.text(`${page} / ${totalPages}`, PAGE_WIDTH / 2, 288, { align: 'center' });
    doc.setTextColor(...TEXT_COLOR);
  }

  return doc.output('blob');
}
