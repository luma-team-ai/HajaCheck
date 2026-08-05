import html2canvas from 'html2canvas';

const PNG_MIME_TYPE = 'image/png';

function buildFileName(facilityId: string): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `회차간비교_${facilityId}_${yyyy}${mm}${dd}.pdf`;
}

// html2canvas는 <select>처럼 OS/브라우저가 직접 그리는 네이티브 폼 컨트롤의 실제 픽셀을 읽어올
// 방법이 없어, 캡처 시 선택된 옵션 텍스트가 겹쳐 그려지는 구조적 한계가 있다(2026-08-05 발견).
// 캡처 직전 캡처 영역 안의 모든 select를 "선택된 옵션 텍스트를 보여주는 동일 스타일 span"으로
// 잠깐 바꿔치기하고, 캡처 성공/실패와 무관하게 즉시 원래 select로 되돌린다 — 사용자에게는
// 화면 변화가 보이지 않고(찰나의 캡처 순간만 대체), 실제 상호작용에는 영향이 없다.
function swapSelectsForCapture(node: HTMLElement): () => void {
  const selects = Array.from(node.querySelectorAll('select'));
  const restorers = selects.map((select) => {
    const selectedOption = select.options[select.selectedIndex];
    const replacement = document.createElement('span');
    replacement.className = select.className;
    replacement.textContent = selectedOption?.text ?? '';

    const originalDisplay = select.style.display;
    select.insertAdjacentElement('afterend', replacement);
    select.style.display = 'none';

    return () => {
      replacement.remove();
      select.style.display = originalDisplay;
    };
  });

  return () => restorers.forEach((restore) => restore());
}

// 메인 콘텐츠 영역(사이드바·헤더 제외)만 캡처해 PDF로 저장한다(#489 확정 범위 유지).
// 이 페이지는 정형 데이터가 아니라 사진·표가 섞인 시각적 레이아웃이라, 표 전용인
// exportReportToPdf/exportDefectsToPdf(jspdf-autotable로 처음부터 그리는 방식)를 재사용하지
// 않고 캡처 이미지를 jsPDF 문서에 그대로 삽입한다(#1371). 콘텐츠가 A4 한 페이지보다 길면
// 같은 이미지를 다음 페이지에 위로 밀어(음수 y) 이어 그려 자동으로 페이지를 나눈다 — PDF
// 페이지는 자기 영역 밖으로 나간 내용을 그리지 않으므로 페이지마다 다른 구간만 보인다.
export async function exportComparisonReportAsPdf(node: HTMLElement, facilityId: string): Promise<void> {
  const jsPdfModulePromise = import('jspdf');
  const restoreSelects = swapSelectsForCapture(node);
  let canvas;
  try {
    canvas = await html2canvas(node);
  } finally {
    restoreSelects();
  }
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

  doc.save(buildFileName(facilityId));
}
