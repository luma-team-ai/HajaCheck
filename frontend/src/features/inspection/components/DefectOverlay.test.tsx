// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Defect, InspectionMedia } from '../types';
import { DefectOverlay } from './DefectOverlay';

// vitest globals 미설정 환경이라 RTL 자동 cleanup이 안 걸림 — 명시 호출 필요
afterEach(() => cleanup());

const media: InspectionMedia = {
  id: 1,
  imageUrl: 'data:image/svg+xml;utf8,mock',
  width: 1600,
  height: 1200,
};

const defects: Defect[] = [
  {
    id: 1,
    type: '균열',
    grade: 'C',
    status: '신규',
    confidence: 0.98,
    bbox: { x: 0.12, y: 0.3, width: 0.18, height: 0.08 },
    widthMm: 3.2,
    lengthMm: 45,
    summary: '수평 방향의 구조적 균열로 판단됨.',
  },
  {
    id: 2,
    type: '박리박락',
    grade: 'B',
    status: '신규',
    confidence: 0.81,
    bbox: { x: 0.55, y: 0.42, width: 0.12, height: 0.15 },
    areaRatio: 0.08,
    summary: '콘크리트 표면 박리 영역 확대 중.',
  },
];

describe('DefectOverlay', () => {
  it('박스 클릭 시 onSelect(id)가 호출된다', () => {
    const onSelect = vi.fn();
    render(<DefectOverlay media={media} defects={defects} onSelect={onSelect} />);

    fireEvent.click(screen.getByTitle(/박리박락/));

    expect(onSelect).toHaveBeenCalledWith(2);
  });

  it('selectedId와 일치하는 박스에만 라벨이 노출된다', () => {
    render(<DefectOverlay media={media} defects={defects} selectedId={2} onSelect={vi.fn()} />);

    expect(screen.getByText('박리박락 B등급')).not.toBeNull();
    expect(screen.queryByText('균열 C등급')).toBeNull();
  });

  it('selectedId가 없으면 어떤 박스에도 라벨이 노출되지 않는다', () => {
    render(<DefectOverlay media={media} defects={defects} onSelect={vi.fn()} />);

    expect(screen.queryByText('박리박락 B등급')).toBeNull();
    expect(screen.queryByText('균열 C등급')).toBeNull();
  });
});
