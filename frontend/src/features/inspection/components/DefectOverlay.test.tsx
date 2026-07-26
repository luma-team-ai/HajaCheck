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
    status: 'DETECTED',
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
    status: 'DETECTED',
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

  it('겹친 박스는 면적 내림차순으로 렌더되어 작은 박스가 항상 클릭 가능하다', () => {
    // 큰 박스와 그 안에 완전히 포함되는 작은 박스
    const largeDefect: Defect = {
      id: 10,
      type: '균열',
      grade: 'D',
      status: 'DETECTED',
      confidence: 0.75,
      bbox: { x: 0.2, y: 0.2, width: 0.6, height: 0.6 }, // 면적 0.36
      widthMm: 2.0,
      lengthMm: 30,
      summary: '큰 박스',
    };
    const smallDefect: Defect = {
      id: 11,
      type: '박리박락',
      grade: 'A',
      status: 'DETECTED',
      confidence: 0.92,
      bbox: { x: 0.35, y: 0.35, width: 0.3, height: 0.3 }, // 면적 0.09, 큰 박스 안에 포함
      areaRatio: 0.05,
      summary: '작은 박스',
    };
    const onSelect = vi.fn();
    const { container } = render(
      <DefectOverlay
        media={media}
        defects={[largeDefect, smallDefect]}
        onSelect={onSelect}
      />,
    );

    // DOM 순서 확인: 면적 내림차순이므로 큰 박스가 먼저, 작은 박스가 나중에 와야 함
    const buttons = container.querySelectorAll('button');
    expect(buttons.length).toBe(2);
    // 두 번째 버튼(나중에 렌더)이 작은 박스여야 함 (title로 구분)
    const largeBoxTitle = buttons[0].getAttribute('title') ?? '';
    const smallBoxTitle = buttons[1].getAttribute('title') ?? '';
    expect(largeBoxTitle).toMatch(/confidence/); // 큰 박스는 정상 defect
    expect(smallBoxTitle).toMatch(/confidence/); // 작은 박스도 정상 defect

    // 작은 박스 클릭 시 onSelect 콜 확인
    fireEvent.click(buttons[1]);
    expect(onSelect).toHaveBeenCalledWith(11);
  });

  it('세로 사진 여백 축소를 위해 이미지 높이 상한이 79vh다(#897)', () => {
    render(<DefectOverlay media={media} defects={defects} onSelect={vi.fn()} />);

    const img = screen.getByAltText('점검 이미지');
    expect(img.className).toContain('max-h-[79vh]');
  });
});
