// 실 하자 사진 자산이 없어 SVG data URI로 대체 이미지를 생성한다 — 외부 네트워크 이미지 의존 없이
// 스토리·테스트에서 동일하게 재현 가능하다(dev-04-02, #489).
const PLACEHOLDER_WIDTH = 640;
const PLACEHOLDER_HEIGHT = 480;

// SVG 텍스트 노드에 보간되므로 &/</>를 이스케이프해 마크업 깨짐을 막는다 — 현재 호출부는
// 전부 정적 라벨이지만 export된 유틸이라 향후 실 데이터 유입 시에도 안전해야 한다(#516 P2 후속).
function escapeXmlText(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export function buildDefectImagePlaceholder(label: string): string {
  const safeLabel = escapeXmlText(label);
  const svg = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${PLACEHOLDER_WIDTH}" height="${PLACEHOLDER_HEIGHT}" viewBox="0 0 ${PLACEHOLDER_WIDTH} ${PLACEHOLDER_HEIGHT}">`,
    `<rect width="${PLACEHOLDER_WIDTH}" height="${PLACEHOLDER_HEIGHT}" fill="#d4d4d8"/>`,
    `<text x="${PLACEHOLDER_WIDTH / 2}" y="${PLACEHOLDER_HEIGHT / 2}" text-anchor="middle" dominant-baseline="middle" font-family="sans-serif" font-size="28" fill="#52525b">${safeLabel}</text>`,
    '</svg>',
  ].join('');

  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}
