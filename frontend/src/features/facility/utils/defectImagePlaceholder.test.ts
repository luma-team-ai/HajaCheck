import { describe, it, expect } from 'vitest';
import { buildDefectImagePlaceholder } from './defectImagePlaceholder';

function decodeSvg(dataUri: string): string {
  return decodeURIComponent(dataUri.replace('data:image/svg+xml,', ''));
}

describe('buildDefectImagePlaceholder', () => {
  it('라벨을 포함한 SVG data URI를 반환한다', () => {
    const svg = decodeSvg(buildDefectImagePlaceholder('원본 이미지'));
    expect(svg).toContain('<svg');
    expect(svg).toContain('원본 이미지');
  });

  it('라벨에 포함된 &, <, >를 이스케이프해 SVG 마크업이 깨지지 않는다', () => {
    const svg = decodeSvg(buildDefectImagePlaceholder('A&B <script>'));
    expect(svg).toContain('A&amp;B &lt;script&gt;');
    expect(svg).not.toContain('<script>');
  });
});
