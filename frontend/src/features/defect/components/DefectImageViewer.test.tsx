// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { DefectImageViewer } from './DefectImageViewer';

afterEach(cleanup);

describe('DefectImageViewer', () => {
  it('imageUrl이 있으면 이미지와 bbox 오버레이를 렌더링한다', () => {
    render(
      <DefectImageViewer
        imageUrl="/api/media/42/thumbnail"
        typeLabel="균열"
        bboxX={0.1}
        bboxY={0.2}
        bboxW={0.3}
        bboxH={0.4}
      />,
    );

    const img = screen.getByRole('img', { name: '균열 촬영 이미지' }) as HTMLImageElement;
    expect(img.src).toContain('/api/media/42/thumbnail');
    const overlay = screen.getByLabelText('AI 감지 영역');
    expect(overlay.style.left).toBe('10%');
    expect(overlay.style.top).toBe('20%');
    expect(overlay.style.width).toBe('30%');
    expect(overlay.style.height).toBe('40%');
  });

  it('bbox가 없으면 오버레이 없이 이미지만 렌더링한다', () => {
    render(
      <DefectImageViewer imageUrl="/api/media/42/thumbnail" typeLabel="균열" bboxX={null} bboxY={null} bboxW={null} bboxH={null} />,
    );

    expect(screen.getByRole('img', { name: '균열 촬영 이미지' })).not.toBeNull();
    expect(screen.queryByLabelText('AI 감지 영역')).toBeNull();
  });

  it('imageUrl이 없으면 빈 상태 메시지를 표시한다', () => {
    render(
      <DefectImageViewer imageUrl={null} typeLabel="균열" bboxX={null} bboxY={null} bboxW={null} bboxH={null} />,
    );

    expect(screen.getByText('촬영 이미지가 없습니다')).not.toBeNull();
    expect(screen.queryByRole('img')).toBeNull();
  });
});
