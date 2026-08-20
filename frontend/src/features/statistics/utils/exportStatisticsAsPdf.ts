import html2canvas from 'html2canvas-pro';

const PNG_MIME_TYPE = 'image/png';
// 인쇄(정부기관 제출 등) 시 가장자리가 잘리지 않도록 상하좌우 10mm 여백을 둔다(2026-08-19 실측 픽스).
const MARGIN_MM = 10;
// A4 두 변의 mm 길이 — orientation에 따라 짧은 변/긴 변이 뒤바뀌므로 이름을 방향과 분리해 둔다.
const A4_SHORT_SIDE_MM = 210;
const A4_LONG_SIDE_MM = 297;

function buildFileName(): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `통계리포트_${yyyy}${mm}${dd}.pdf`;
}

// 캡처 클론 전처리 — 캡처에서만 적용되고 실제 화면 DOM/스타일은 건드리지 않는다(onclone은
// html2canvas가 캡처용으로 만든 별도 클론 문서에서 실행된다).
// 1) data-export-ignore="true"가 붙은 요소(StatisticsFilterBar의 "내보내기" 버튼)는 리포트에
//    찍히면 부자연스러우므로 캡처에서 뺀다. html2canvas-pro의 두 방법(ignoreElements로 클론에서
//    아예 제거 / onclone에서 visibility만 숨기기) 중 onclone+visibility:hidden을 택했다 —
//    ignoreElements는 클론 DOM에서 요소를 통째로 들어내므로, 이 버튼이 속한 우측 정렬
//    `flex gap-3` 컨테이너의 폭이 줄어들며 남은 필터 드롭다운 2개가 화면에서 보던 위치보다
//    오른쪽으로 밀려 보인다(레이아웃 재배치). visibility:hidden은 버튼 자리(폭)를 그대로 두고
//    색만 지우므로 드롭다운 위치가 화면과 완전히 동일하게 유지된다.
// 2) letter-spacing을 전역 normal로 무력화한다. html2canvas-pro는 letter-spacing이 걸린 텍스트를
//    글자 단위로 쪼개 캔버스에 직접 그리는데, 한 텍스트에 숫자(라틴)+한글이 섞이면 한글 글자의
//    baseline/크기가 어긋난다(#1732 — KPI 값 span의 `tracking-tight` -0.025em으로 실측 재현).
//    화면 스타일까지 normal로 바꾸면 사용자가 보는 화면과 리포트가 달라지므로, 캡처 클론의
//    head에만 `* { letter-spacing: normal !important; }`를 주입한다(라이브 DOM에서 letterSpacing을
//    normal로 바꿔 내보내면 정상 렌더되는 것을 메인이 실제 PDF로 확인함). tracking-tight로 인한
//    폭 차이는 KPI 숫자 기준 몇 px 수준이라 레이아웃에 미치는 영향은 무시할 수 있다.
function prepareCloneForCapture(clonedDoc: Document): void {
  clonedDoc.querySelectorAll<HTMLElement>('[data-export-ignore="true"]').forEach((element) => {
    element.style.visibility = 'hidden';
  });
  const letterSpacingReset = clonedDoc.createElement('style');
  letterSpacingReset.textContent = '* { letter-spacing: normal !important; }';
  clonedDoc.head.appendChild(letterSpacingReset);
}

// 캡처 캔버스 비율에 맞는 A4 방향을 고른다 — landscape/portrait 각각으로 캔버스를 콘텐츠
// 영역(여백 제외)에 맞출 때의 축소 배율을 구해 더 큰 쪽(=이미지가 더 크게 들어가는 쪽)을
// 선택한다. 같으면 기존 동작(가로 레이아웃 대시보드 기준)과의 호환을 위해 landscape를 쓴다.
function pickOrientation(canvasWidth: number, canvasHeight: number): 'landscape' | 'portrait' {
  const landscapeScale = Math.min(
    (A4_LONG_SIDE_MM - MARGIN_MM * 2) / canvasWidth,
    (A4_SHORT_SIDE_MM - MARGIN_MM * 2) / canvasHeight,
  );
  const portraitScale = Math.min(
    (A4_SHORT_SIDE_MM - MARGIN_MM * 2) / canvasWidth,
    (A4_LONG_SIDE_MM - MARGIN_MM * 2) / canvasHeight,
  );
  return landscapeScale >= portraitScale ? 'landscape' : 'portrait';
}

// #1692 — 통계 화면 "내보내기"를 CSV(숫자 나열)에서 화면 캡처 PDF로 전환한다. 회차 간 비교
// 화면의 exportComparisonReportAsPdf(#489/#1371)와 동일한 접근: html2canvas로 캡처 대상을
// 이미지로 뜨고, jsPDF a4 문서에 그대로 삽입한다.
// #1732 — 페이지 분할(폭 기준 스케일 + 넘치는 높이를 다음 페이지로 자르는 방식)을 제거하고
// 한 페이지 축소 배치로 바꿨다. 1512px 뷰포트 기준 캡처는 1195x1216(거의 정사각)이라 A4
// 가로 콘텐츠(277x190mm)에 폭만 맞추면 높이가 282mm가 되어 2페이지로 잘렸다(메인 실측). 대신
// 캡처 전체가 한 페이지 콘텐츠 영역 안에 들어가도록 폭/높이 중 더 작아지는 배율로 축소하고,
// 방향도 캡처 비율에 맞춰 자동 선택한다 — 리포트는 항상 정확히 1페이지가 된다.
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
  const canvas = await html2canvas(node, { onclone: prepareCloneForCapture });
  const { default: jsPDF } = await jsPdfModulePromise;

  // PR머신 P1 픽스(#1732에서도 유지) — canvas.width/height가 0이면 축소 배율 계산이 무의미하고
  // addImage에 빈 이미지를 그리게 된다. 조용히 빈 PDF를 저장하는 대신 도메인 에러로 실패시켜
  // 호출부(StatisticsPage.handleExport)가 catch해 기존 에러 배너로 사용자에게 실패를 드러낸다.
  // (예전엔 while 루프의 무한루프 방지 목적도 있었으나, 페이지 분할 자체가 사라져 그 사유는 없어졌다.)
  if (canvas.width <= 0 || canvas.height <= 0) {
    throw new Error('화면 캡처 결과가 비어 있어 PDF를 만들 수 없습니다.');
  }

  const doc = new jsPDF({
    unit: 'mm',
    format: 'a4',
    orientation: pickOrientation(canvas.width, canvas.height),
  });
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const scale = Math.min(
    (pageWidth - MARGIN_MM * 2) / canvas.width,
    (pageHeight - MARGIN_MM * 2) / canvas.height,
  );
  const imageWidthMm = canvas.width * scale;
  const imageHeightMm = canvas.height * scale;

  // 콘텐츠 영역 안에서 가로·세로 모두 가운데 정렬 — 캡처 비율과 페이지 비율이 정확히 같지
  // 않은 한 여백이 남는데, 한쪽으로 붙지 않고 균등하게 보이도록 중앙에 배치한다.
  doc.addImage(
    canvas.toDataURL(PNG_MIME_TYPE),
    'PNG',
    (pageWidth - imageWidthMm) / 2,
    (pageHeight - imageHeightMm) / 2,
    imageWidthMm,
    imageHeightMm,
  );

  doc.save(buildFileName());
}
