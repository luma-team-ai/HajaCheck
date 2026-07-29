import pretendardRegularUrl from 'pretendard/dist/public/static/alternative/Pretendard-Regular.ttf?url';
import pretendardBoldUrl from 'pretendard/dist/public/static/alternative/Pretendard-Bold.ttf?url';
import { STATUS_PRESENTATION } from '../constants/defectPresentation';
import type { Defect } from '../types';
import { formatDefectCode, formatDefectDate } from './defectFormat';

const FONT_REGULAR_FILE_NAME = 'Pretendard-Regular.ttf';
const FONT_BOLD_FILE_NAME = 'Pretendard-Bold.ttf';
const FONT_NAME = 'Pretendard';
const EXPORT_HEADERS = ['하자 ID', '유형', '등급', '시설물', '상태', '발견일'];

// jsPDF 기본 폰트(Helvetica 등)는 한글을 지원하지 않아 그대로 쓰면 텍스트가 비거나 깨진다.
// Pretendard regular/bold(OFL-1.1)를 base64로 임베딩해야 표의 한글 컬럼·값과 굵은 헤더가 정상 렌더링된다
// (frontend/src/features/admin/components/AdminUserPrintTable.tsx의 동일 이슈 코멘트 참고, 사용자 확인 완료).
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

function buildFileName(): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `하자목록_${yyyy}${mm}${dd}.pdf`;
}

export function buildDefectExportRows(defects: Defect[]): string[][] {
  return defects.map((defect) => [
    formatDefectCode(defect.id),
    defect.typeLabel,
    defect.grade ?? '-',
    defect.facilityName,
    STATUS_PRESENTATION[defect.status].label,
    formatDefectDate(defect.createdAt),
  ]);
}

// 선택된 하자 행을 표 형식 그대로 클라이언트에서 PDF로 내보낸다(서버 호출 없음).
// jsPDF/jspdf-autotable/폰트는 번들 크기 때문에 클릭 시점에만 동적 import한다.
export async function exportDefectsToPdf(defects: Defect[]): Promise<void> {
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

  const doc = new jsPDF();
  doc.addFileToVFS(FONT_REGULAR_FILE_NAME, regularFontBase64);
  doc.addFileToVFS(FONT_BOLD_FILE_NAME, boldFontBase64);
  doc.addFont(FONT_REGULAR_FILE_NAME, FONT_NAME, 'normal');
  doc.addFont(FONT_BOLD_FILE_NAME, FONT_NAME, 'bold');

  // 관리자 사용자 목록 인쇄 양식과 동일하게 제목 → 생성 시각·건수 → 검은 테두리 표 순서로 구성한다.
  doc.setFont(FONT_NAME, 'bold');
  doc.setFontSize(16);
  doc.text('하자 목록', 14, 15);
  doc.setFont(FONT_NAME, 'normal');
  doc.setFontSize(9);
  doc.text(`내보낸 시각: ${new Date().toLocaleString('ko-KR')} · 총 ${defects.length}건`, 14, 22);

  autoTable(doc, {
    head: [EXPORT_HEADERS],
    body: buildDefectExportRows(defects),
    startY: 28,
    theme: 'grid',
    styles: {
      font: FONT_NAME,
      fontStyle: 'normal',
      fontSize: 8,
      textColor: [0, 0, 0],
      lineColor: [0, 0, 0],
      lineWidth: 0.1,
      halign: 'left',
      cellPadding: 2,
    },
    headStyles: {
      font: FONT_NAME,
      fontStyle: 'bold',
      fillColor: [255, 255, 255],
      textColor: [0, 0, 0],
      lineColor: [0, 0, 0],
      lineWidth: 0.1,
      halign: 'left',
    },
    alternateRowStyles: {
      fillColor: [255, 255, 255],
    },
  });

  doc.save(buildFileName());
}
