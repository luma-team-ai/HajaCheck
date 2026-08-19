import html2canvas from 'html2canvas-pro';

const PNG_MIME_TYPE = 'image/png';
// 인쇄(정부기관 제출 등) 시 가장자리가 잘리지 않도록 상하좌우 10mm 여백을 둔다(2026-08-19 실측 픽스).
const MARGIN_MM = 10;

function buildFileName(): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `통계리포트_${yyyy}${mm}${dd}.pdf`;
}

// 캡처 캔버스에서 [sourceY, sourceY+sliceHeightPx) 구간만 잘라 별도 캔버스에 그리고 PNG로 반환한다.
// 여백을 둔 채로 "같은 이미지를 음수 y로 밀어 다음 페이지에 이어 그리는" 예전 방식(선례와 동일)을
// 쓰면, 앞 페이지에서 여백 밖으로 넘친 부분이 다음 페이지의 상단 여백 영역에 그대로 드러난다
// (addImage는 페이지 캔버스 좌표 기준으로 그리지, 콘텐츠 영역으로 클리핑해주지 않는다). 그래서
// 페이지 단위로 소스 캔버스 자체를 잘라 조각마다 독립된 이미지로 넣는 방식으로 바꿨다.
function sliceCanvasToDataUrl(source: HTMLCanvasElement, sourceY: number, sliceHeightPx: number): string {
  const sliceCanvas = document.createElement('canvas');
  sliceCanvas.width = source.width;
  sliceCanvas.height = sliceHeightPx;
  const context = sliceCanvas.getContext('2d');
  if (!context) {
    throw new Error('캡처 이미지를 페이지 단위로 자르기 위한 2D 캔버스 컨텍스트를 생성할 수 없습니다.');
  }
  context.drawImage(source, 0, sourceY, source.width, sliceHeightPx, 0, 0, source.width, sliceHeightPx);
  return sliceCanvas.toDataURL(PNG_MIME_TYPE);
}

// data-export-ignore="true"가 붙은 요소(StatisticsFilterBar의 "내보내기" 버튼)는 리포트에
// 찍히면 부자연스러우므로 캡처에서 뺀다. html2canvas-pro의 두 방법(ignoreElements로 클론에서
// 아예 제거 / onclone에서 visibility만 숨기기) 중 onclone+visibility:hidden을 택했다 —
// ignoreElements는 클론 DOM에서 요소를 통째로 들어내므로, 이 버튼이 속한 우측 정렬
// `flex gap-3` 컨테이너의 폭이 줄어들며 남은 필터 드롭다운 2개가 화면에서 보던 위치보다
// 오른쪽으로 밀려 보인다(레이아웃 재배치). visibility:hidden은 버튼 자리(폭)를 그대로 두고
// 색만 지우므로 드롭다운 위치가 화면과 완전히 동일하게 유지된다 — "화면을 그대로 찍는다"는
// 이번 기능의 취지에 더 맞는 쪽은 후자다.
function hideExportIgnoredElements(clonedDoc: Document): void {
  clonedDoc.querySelectorAll<HTMLElement>('[data-export-ignore="true"]').forEach((element) => {
    element.style.visibility = 'hidden';
  });
}

// #1692 — 통계 화면 "내보내기"를 CSV(숫자 나열)에서 화면 캡처 PDF로 전환한다. 회차 간 비교
// 화면의 exportComparisonReportAsPdf(#489/#1371)와 동일한 접근: html2canvas로 캡처 대상을
// 이미지로 뜨고, jsPDF a4/mm 문서에 그대로 삽입한다. 콘텐츠가 A4 한 페이지보다 길면 페이지
// 콘텐츠 영역(여백 제외) 높이만큼 소스 캔버스를 잘라 페이지마다 별도 이미지로 그린다.
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
  const canvas = await html2canvas(node, { onclone: hideExportIgnoredElements });
  const { default: jsPDF } = await jsPdfModulePromise;

  const doc = new jsPDF({ unit: 'mm', format: 'a4' });
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const contentWidthMm = pageWidth - MARGIN_MM * 2;
  const contentHeightMm = pageHeight - MARGIN_MM * 2;
  const pxPerMm = canvas.width / contentWidthMm;
  const pageSliceHeightPx = Math.round(contentHeightMm * pxPerMm);

  let sourceY = 0;
  let isFirstPage = true;
  while (sourceY < canvas.height) {
    // 마지막 조각은 남은 높이만큼만 자른다 — 페이지 높이만큼 늘려 그리면 이미지가 늘어져 보인다.
    const sliceHeightPx = Math.min(pageSliceHeightPx, canvas.height - sourceY);
    const sliceDataUrl = sliceCanvasToDataUrl(canvas, sourceY, sliceHeightPx);

    if (!isFirstPage) {
      doc.addPage();
    }
    doc.addImage(sliceDataUrl, 'PNG', MARGIN_MM, MARGIN_MM, contentWidthMm, sliceHeightPx / pxPerMm);

    sourceY += sliceHeightPx;
    isFirstPage = false;
  }

  doc.save(buildFileName());
}
