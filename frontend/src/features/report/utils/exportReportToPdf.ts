import pretendardRegularUrl from 'pretendard/dist/public/static/alternative/Pretendard-Regular.ttf?url';
import type { ReportContent } from '../types';

const FONT_FILE_NAME = 'Pretendard-Regular.ttf';
const FONT_NAME = 'Pretendard';
const PAGE_MARGIN_X = 14;
const PAGE_WIDTH = 210; // A4 mm
const CONTENT_WIDTH = PAGE_WIDTH - PAGE_MARGIN_X * 2;
const LINE_HEIGHT = 6;
const PAGE_BOTTOM = 285;

// jsPDF 기본 폰트는 한글을 지원하지 않아 Pretendard를 base64로 임베딩한다
// (frontend/src/features/defect/utils/exportDefectsToPdf.ts와 동일 패턴 재사용).
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

export function buildReportPdfFileName(inspectionId: number): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `점검보고서_${inspectionId}_${yyyy}${mm}${dd}.pdf`;
}

// 편집된 보고서 content를 클라이언트에서 PDF로 렌더링해 Blob으로 반환한다(서버 업로드용).
// jsPDF/폰트는 번들 크기 때문에 클릭 시점에만 동적 import한다.
export async function exportReportToPdf(content: ReportContent): Promise<Blob> {
  const [{ default: jsPDF }, fontResponse] = await Promise.all([
    import('jspdf'),
    fetch(pretendardRegularUrl),
  ]);
  const fontBase64 = await toBase64(await fontResponse.blob());

  const doc = new jsPDF();
  doc.addFileToVFS(FONT_FILE_NAME, fontBase64);
  doc.addFont(FONT_FILE_NAME, FONT_NAME, 'normal');
  doc.setFont(FONT_NAME);

  let y = 20;
  const ensureSpace = (lines: number) => {
    if (y + lines * LINE_HEIGHT > PAGE_BOTTOM) {
      doc.addPage();
      doc.setFont(FONT_NAME);
      y = 20;
    }
  };
  const writeHeading = (text: string) => {
    ensureSpace(2);
    doc.setFontSize(14);
    doc.text(text, PAGE_MARGIN_X, y);
    y += LINE_HEIGHT + 2;
    doc.setFontSize(10);
  };
  const writeParagraph = (label: string, value: string) => {
    const wrapped = doc.splitTextToSize(`${label}: ${value || '-'}`, CONTENT_WIDTH);
    ensureSpace(wrapped.length);
    doc.text(wrapped, PAGE_MARGIN_X, y);
    y += wrapped.length * LINE_HEIGHT;
  };
  const writeLine = (text: string) => {
    const wrapped = doc.splitTextToSize(text, CONTENT_WIDTH);
    ensureSpace(wrapped.length);
    doc.text(wrapped, PAGE_MARGIN_X, y);
    y += wrapped.length * LINE_HEIGHT;
  };

  doc.setFontSize(18);
  doc.text('점검 보고서', PAGE_MARGIN_X, y);
  y += LINE_HEIGHT + 4;
  doc.setFontSize(10);

  writeHeading('1. 개요');
  writeParagraph('점검 목적', content.overview.purpose);
  writeParagraph('시설물 개요', content.overview.facility_summary);
  writeParagraph('점검 범위', content.overview.scope);
  y += 4;

  writeHeading('2. 요약');
  writeParagraph('종합 의견', content.summary.overall_opinion);
  writeParagraph('총 하자 수', String(content.summary.total_count));
  writeParagraph(
    '등급별 개수',
    Object.entries(content.summary.count_by_grade)
      .map(([grade, count]) => `${grade}등급 ${count}건`)
      .join(', '),
  );
  writeLine('주요 발견사항:');
  content.summary.key_findings.forEach((finding, i) => writeLine(`  ${i + 1}. ${finding}`));
  y += 4;

  writeHeading('3. 상세 내역');
  content.detail.items.forEach((item, i) => {
    writeLine(`${i + 1}) ${item.defect_type} / ${item.location} / ${item.severity_grade}등급`);
    writeParagraph('  설명', item.description);
    writeParagraph('  원인', item.cause);
  });
  y += 4;

  writeHeading('4. 조치 권고');
  content.recommendation.items.forEach((item, i) => {
    writeLine(`${i + 1}) 대상: ${item.target} / 우선순위: ${item.priority}`);
    writeParagraph('  방법', item.method);
    writeParagraph(
      '  근거',
      `${item.legal_basis}${item.legal_basis_verified ? ' (검증됨)' : ''}`,
    );
  });
  if (content.recommendation.monitoring_points.length > 0) {
    writeLine('모니터링 포인트:');
    content.recommendation.monitoring_points.forEach((point, i) => writeLine(`  ${i + 1}. ${point}`));
  }

  return doc.output('blob');
}
