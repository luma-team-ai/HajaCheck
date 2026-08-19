import html2canvas from 'html2canvas-pro';

const PNG_MIME_TYPE = 'image/png';

function buildFileName(): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `통계리포트_${yyyy}${mm}${dd}.pdf`;
}

// #1692 — 통계 화면 "내보내기"를 CSV(숫자 나열)에서 화면 캡처 PDF로 전환한다. 회차 간 비교
// 화면의 exportComparisonReportAsPdf(#489/#1371)와 동일한 접근: html2canvas로 캡처 대상을
// 이미지로 뜨고, jsPDF a4/mm 문서에 그대로 삽입한다. 콘텐츠가 A4 한 페이지보다 길면 같은
// 이미지를 다음 페이지에 위로 밀어(음수 y) 이어 그려 자동으로 페이지를 나눈다.
// jsPDF는 통계 화면 진입 시점엔 쓰이지 않는 번들이라 동적 import로 분리하고(선례와 동일),
// html2canvas는 내보내기 클릭 시 곧바로 필요해 선례와 동일하게 정적 import를 유지한다.
// 통계 화면엔 회차 비교 화면과 달리 native <select>가 없어(커스텀 팝오버 드롭다운뿐),
// select를 캡처용 span으로 바꿔치기하는 swapSelectsForCapture는 옮겨오지 않는다.
// html2canvas(원본, 1.4.1)가 아니라 html2canvas-pro를 쓴다 — Tailwind v4 기본 팔레트(zinc/gray
// 등)가 oklch로 컴파일되는데, 원본의 SUPPORTED_COLOR_FUNCTIONS는 rgb/rgba/hsl/hsla 4개뿐이라
// oklch 색을 만나면 "Attempting to parse an unsupported color function" 예외를 던져 캡처
// 전체가 실패한다(2026-08-19 실측: Chrome getComputedStyle이 oklch(...)를 그대로 반환).
// html2canvas-pro는 oklch/oklab/color() 등을 지원하는 포크라 이 문제가 없다.
export async function exportStatisticsAsPdf(node: HTMLElement): Promise<void> {
  const jsPdfModulePromise = import('jspdf');
  const canvas = await html2canvas(node);
  const { default: jsPDF } = await jsPdfModulePromise;
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

  doc.save(buildFileName());
}
