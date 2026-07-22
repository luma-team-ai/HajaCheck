import pretendardRegularUrl from 'pretendard/dist/public/static/alternative/Pretendard-Regular.ttf?url';
import { STATUS_PRESENTATION } from '../components/DefectTable';
import type { Defect } from '../types';

const FONT_FILE_NAME = 'Pretendard-Regular.ttf';
const FONT_NAME = 'Pretendard';
const EXPORT_HEADERS = ['하자 ID', '유형', '등급', '시설물', '상태', '발견일'];

// jsPDF 기본 폰트(Helvetica 등)는 한글을 지원하지 않아 그대로 쓰면 텍스트가 비거나 깨진다.
// Pretendard(OFL-1.1)를 base64로 임베딩해야 표의 한글 컬럼·값이 정상 렌더링된다
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
    `DEF-${String(defect.id).padStart(4, '0')}`,
    defect.typeLabel,
    defect.grade ?? '-',
    defect.facilityName,
    STATUS_PRESENTATION[defect.status].label,
    defect.createdAt.slice(2, 10).replaceAll('-', '.'),
  ]);
}

// 선택된 하자 행을 표 형식 그대로 클라이언트에서 PDF로 내보낸다(서버 호출 없음).
// jsPDF/jspdf-autotable/폰트는 번들 크기 때문에 클릭 시점에만 동적 import한다.
export async function exportDefectsToPdf(defects: Defect[]): Promise<void> {
  const [{ default: jsPDF }, { default: autoTable }, fontResponse] = await Promise.all([
    import('jspdf'),
    import('jspdf-autotable'),
    fetch(pretendardRegularUrl),
  ]);
  const fontBase64 = await toBase64(await fontResponse.blob());

  const doc = new jsPDF();
  doc.addFileToVFS(FONT_FILE_NAME, fontBase64);
  doc.addFont(FONT_FILE_NAME, FONT_NAME, 'normal');
  doc.setFont(FONT_NAME);

  doc.text('하자 목록', 14, 15);
  autoTable(doc, {
    head: [EXPORT_HEADERS],
    body: buildDefectExportRows(defects),
    startY: 20,
    styles: { font: FONT_NAME },
    headStyles: { font: FONT_NAME },
  });

  doc.save(buildFileName());
}
