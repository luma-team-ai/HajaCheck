import html2canvas from 'html2canvas';

const PNG_MIME_TYPE = 'image/png';

function buildFileName(facilityId: string): string {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  return `회차간비교_${facilityId}_${yyyy}${mm}${dd}.png`;
}

// 메인 콘텐츠 영역(사이드바·헤더 제외)만 PNG로 캡처해 브라우저 기본 다운로드로 저장한다.
// 저장 위치 선택 팝업은 만들지 않는다 — File System Access API는 파이어폭스·사파리 미지원이라
// 크로스브라우저 호환을 위해 <a download> 클릭 트리거 방식만 사용한다(#489 확정).
export async function exportComparisonReportAsPng(node: HTMLElement, facilityId: string): Promise<void> {
  const canvas = await html2canvas(node);
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, PNG_MIME_TYPE));
  if (!blob) {
    throw new Error('PNG 변환에 실패했습니다.');
  }

  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = buildFileName(facilityId);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}