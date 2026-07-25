import { describe, expect, it } from 'vitest';
import { resolveDefectImageUrl } from './useInspectionResultReal';

// PR머신 리뷰 P2(#789) — imageUrl: d.detailUrl ?? d.imageUrl ?? null 변경으로 뷰어에 실제 노출되는
// 이미지 URL의 우선순위가 바뀌었는데 이를 고정하는 테스트가 없었다. 우선순위가 반대로 바뀌거나
// detailUrl 처리가 깨져도 감지되지 않던 문제를 순수 함수로 분리해 직접 검증한다.
describe('resolveDefectImageUrl', () => {
  it('detailUrl이 있으면 detailUrl을 우선 사용한다', () => {
    expect(resolveDefectImageUrl('/api/media/1/thumbnail', '/api/media/1/detail')).toBe(
      '/api/media/1/detail',
    );
  });

  it('detailUrl이 없으면(null) imageUrl로 폴백한다', () => {
    expect(resolveDefectImageUrl('/api/media/1/thumbnail', null)).toBe('/api/media/1/thumbnail');
  });

  it('detailUrl이 없으면(undefined) imageUrl로 폴백한다', () => {
    expect(resolveDefectImageUrl('/api/media/1/thumbnail', undefined)).toBe(
      '/api/media/1/thumbnail',
    );
  });

  it('둘 다 없으면 null을 반환한다(수동 추가 하자 등 mediaId 없는 경우)', () => {
    expect(resolveDefectImageUrl(null, null)).toBeNull();
    expect(resolveDefectImageUrl(undefined, undefined)).toBeNull();
  });
});
