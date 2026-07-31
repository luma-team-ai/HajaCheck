import html2canvas from 'html2canvas';

const PNG_MIME_TYPE = 'image/png';

function buildFileName(facilityId: string): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `회차간비교_${facilityId}_${yyyy}${mm}${dd}.pdf`;
}

// 메인 콘텐츠 영역(사이드바·헤더 제외)만 캡처해 PDF로 저장한다(#489 확정 범위 유지).
// 이 페이지는 정형 데이터가 아니라 사진·표가 섞인 시각적 레이아웃이라, 표 전용인
// exportReportToPdf/exportDefectsToPdf(jspdf-autotable로 처음부터 그리는 방식)를 재사용하지
// 않고 캡처 이미지를 jsPDF 문서에 그대로 삽입한다(#1371). 콘텐츠가 A4 한 페이지보다 길면
// 같은 이미지를 다음 페이지에 위로 밀어(음수 y) 이어 그려 자동으로 페이지를 나눈다 — PDF
// 페이지는 자기 영역 밖으로 나간 내용을 그리지 않으므로 페이지마다 다른 구간만 보인다.
export async function exportComparisonReportAsPdf(node: HTMLElement, facilityId: string): Promise<void> {
  const [{ default: jsPDF }, canvas] = await Promise.all([import('jspdf'), html2canvas(node)]);
  const imageData = canvas.toDataURL(PNG_MIME_TYPE);

  const doc = new jsPDF({ unit: 'mm', format: 'a4' });
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const imageWidth = pageWidth;
  const imageHeight = (canvas.height * imageWidth) / canvas.width;

  let heightRemaining = imageHeight;
  let positionY = 0;
  doc.addImage(imageData, 'PNG', 0, positionY, imageWidth, imageHeight);
  heightRemaining -= pageHeight;

  while (heightRemaining > 0) {
    positionY = heightRemaining - imageHeight;
    doc.addPage();
    doc.addImage(imageData, 'PNG', 0, positionY, imageWidth, imageHeight);
    heightRemaining -= pageHeight;
  }

  doc.save(buildFileName(facilityId));
}
