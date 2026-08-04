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
    // 박스는 이미지 로드 완료 후에만 그려진다(페이즈5) — jsdom은 실제 이미지 로딩을 하지 않아
    // load 이벤트를 직접 쏴줘야 한다.
    fireEvent.load(screen.getByAltText('점검 이미지'));

    fireEvent.click(screen.getByTitle(/박리박락/));

    expect(onSelect).toHaveBeenCalledWith(2);
  });

  it('selectedId와 일치하는 박스에만 라벨이 노출된다', () => {
    render(<DefectOverlay media={media} defects={defects} selectedId={2} onSelect={vi.fn()} />);
    fireEvent.load(screen.getByAltText('점검 이미지'));

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
    fireEvent.load(screen.getByAltText('점검 이미지'));

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

  // #1395 — bbox가 없는 하자는 예전엔 `null * 100 = 0` 으로 0×0 픽셀 버튼이 되어 클릭 자체가
  // 불가능했다. 이 화면의 유일한 선택 수단이 박스 클릭이라 그 하자는 영영 검수할 수 없었고,
  // totalCount 분모에는 남아 "점검 요약"이 잠겼다.
  describe('bbox 없는 하자(위치 미지정)', () => {
    const unplaced: Defect = {
      id: 7,
      type: '누수·백태',
      grade: 'D',
      status: 'DETECTED',
      confidence: 1,
      bbox: { x: null, y: null, width: null, height: null } as unknown as Defect['bbox'],
      summary: '박스 없이 추가된 하자',
    };

    it('이미지 위 박스가 아니라 선택 가능한 칩으로 렌더된다', () => {
      const onSelect = vi.fn();
      render(<DefectOverlay media={media} defects={[...defects, unplaced]} onSelect={onSelect} />);

      expect(screen.getByText('위치 미지정:')).not.toBeNull();
      fireEvent.click(screen.getByText('누수·백태 D등급'));
      expect(onSelect).toHaveBeenCalledWith(7);
    });

    it('bbox가 정상인 하자는 칩이 아니라 기존 박스로 남는다', () => {
      render(<DefectOverlay media={media} defects={defects} onSelect={vi.fn()} />);

      expect(screen.queryByText('위치 미지정:')).toBeNull();
    });

    it('0 크기 bbox도 위치 미지정으로 취급한다', () => {
      const zeroSized: Defect = {
        ...unplaced,
        id: 8,
        bbox: { x: 0, y: 0, width: 0, height: 0 },
      };
      render(<DefectOverlay media={media} defects={[zeroSized]} onSelect={vi.fn()} />);

      expect(screen.getByText('위치 미지정:')).not.toBeNull();
    });
  });

  // 등급 미판정(grade=null)이 "null등급"으로 새어 나가지 않아야 한다(#1395).
  it('등급 미판정 하자는 라벨·title에 "등급 미판정"으로 표기된다', () => {
    const ungraded: Defect = { ...defects[0], id: 5, grade: null };
    render(<DefectOverlay media={media} defects={[ungraded]} selectedId={5} onSelect={vi.fn()} />);
    fireEvent.load(screen.getByAltText('점검 이미지'));

    expect(screen.getByText('균열 등급 미판정')).not.toBeNull();
    expect(screen.queryByText(/null등급/)).toBeNull();
  });
});
