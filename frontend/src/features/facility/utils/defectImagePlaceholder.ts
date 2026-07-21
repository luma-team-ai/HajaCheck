// 실 하자 사진 자산이 없어 SVG data URI로 대체 이미지를 생성한다 — 외부 네트워크 이미지 의존 없이
// 스토리·테스트에서 동일하게 재현 가능하다(dev-04-02, #489).
const PLACEHOLDER_WIDTH = 640;
const PLACEHOLDER_HEIGHT = 480;

export function buildDefectImagePlaceholder(label: string): string {
  const svg = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${PLACEHOLDER_WIDTH}" height="${PLACEHOLDER_HEIGHT}" viewBox="0 0 ${PLACEHOLDER_WIDTH} ${PLACEHOLDER_HEIGHT}">`,
    `<rect width="${PLACEHOLDER_WIDTH}" height="${PLACEHOLDER_HEIGHT}" fill="#d4d4d8"/>`,
    `<text x="${PLACEHOLDER_WIDTH / 2}" y="${PLACEHOLDER_HEIGHT / 2}" text-anchor="middle" dominant-baseline="middle" font-family="sans-serif" font-size="28" fill="#52525b">${label}</text>`,
    '</svg>',
  ].join('');

  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

// "오버레이" 탭 전용 마킹 레이어 — 배경은 투명(none)이라 원본 이미지 위에 absolute로 얹으면
// AI 탐지 마킹(빨간 폴리곤+치수 라벨)만 토글되는 것처럼 보인다. 실 자산이 없어 동일 이미지에
// CSS 레이어만 조건부 렌더하는 방식(FacilityDefectImagePanel 참고).
export function buildDefectOverlayMarkingImage(): string {
  const svg = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${PLACEHOLDER_WIDTH}" height="${PLACEHOLDER_HEIGHT}" viewBox="0 0 ${PLACEHOLDER_WIDTH} ${PLACEHOLDER_HEIGHT}">`,
    '<polygon points="270,50 330,50 305,430 295,430" fill="#ef4444" fill-opacity="0.6" />',
    `<text x="${PLACEHOLDER_WIDTH / 2}" y="${PLACEHOLDER_HEIGHT - 30}" text-anchor="middle" font-family="sans-serif" font-size="18" font-weight="bold" fill="#ffffff">CRACK 01 · WIDTH 4mm</text>`,
    '</svg>',
  ].join('');

  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}