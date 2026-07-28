// @vitest-environment jsdom
// DefectCardGrid 단위 테스트 — Figma 정렬(#937)로 상태 select가 탭으로, 유형/등급 select가 퍼넬
// 드롭다운으로 바뀌면서 새로 생긴 필터 UI 로직(상태 탭 클릭 필터링, 퍼넬 패널 열림+필터 적용)을 검증.
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Defect } from '../types';
import { DefectCardGrid } from './DefectCardGrid';

afterEach(() => cleanup());

function makeDefect(overrides: Partial<Defect> & Pick<Defect, 'id'>): Defect {
  return {
    inspectionId: 101,
    facilityId: 1,
    facilityName: '강남 오피스타워 A동',
    facilityType: '건물',
    type: 'CRACK',
    typeLabel: '균열',
    grade: 'C',
    status: 'CONFIRMED',
    confidence: 0.8,
    reviewed: true,
    bboxX: null,
    bboxY: null,
    bboxW: null,
    bboxH: null,
    crackWidthMm: null,
    crackLengthMm: null,
    imageUrl: null,
    createdAt: '2026-07-01T09:00:00.000Z',
    ...overrides,
  };
}

const defects: Defect[] = [
  makeDefect({ id: 1, type: 'CRACK', typeLabel: '균열', grade: 'C', status: 'CONFIRMED' }),
  makeDefect({ id: 2, type: 'REBAR_EXPOSURE', typeLabel: '철근 노출', grade: 'D', status: 'IN_PROGRESS' }),
  makeDefect({ id: 3, type: 'SPALLING', typeLabel: '박리·박락', grade: 'E', status: 'RESOLVED' }),
];

const detectedDefect = makeDefect({
  id: 4,
  type: 'PAINT_DAMAGE',
  typeLabel: '도장 손상',
  grade: 'B',
  status: 'DETECTED',
  reviewed: false,
});

describe('DefectCardGrid — 상태 탭 필터', () => {
  it('전체 탭은 모든 카드를 보여주고 각 탭에 건수를 표시한다', () => {
    render(<DefectCardGrid defects={defects} onSelectDefect={vi.fn()} />);

    const tabs = screen.getByRole('tablist', { name: '상태 필터' });
    expect(within(tabs).getByRole('tab', { name: '전체 3' })).not.toBeNull();
    expect(within(tabs).getByRole('tab', { name: '검수확정 1' })).not.toBeNull();
    expect(within(tabs).getByRole('tab', { name: '조치중 1' })).not.toBeNull();
    expect(within(tabs).getByRole('tab', { name: '조치완료 1' })).not.toBeNull();

    expect(screen.getByRole('button', { name: '균열 하자 상세 보기' })).not.toBeNull();
    expect(screen.getByRole('button', { name: '철근 노출 하자 상세 보기' })).not.toBeNull();
    expect(screen.getByRole('button', { name: '박리·박락 하자 상세 보기' })).not.toBeNull();
  });

  it('조치중 탭을 클릭하면 조치중 상태 카드만 보여준다', () => {
    render(<DefectCardGrid defects={defects} onSelectDefect={vi.fn()} />);

    fireEvent.click(screen.getByRole('tab', { name: '조치중 1' }));

    expect(screen.getByRole('button', { name: '철근 노출 하자 상세 보기' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: '균열 하자 상세 보기' })).toBeNull();
    expect(screen.queryByRole('button', { name: '박리·박락 하자 상세 보기' })).toBeNull();
  });

  it('DETECTED 하자는 statusFilter 값과 무관하게 렌더링하지 않는다', () => {
    render(<DefectCardGrid defects={[...defects, detectedDefect]} onSelectDefect={vi.fn()} />);

    expect(screen.queryByRole('button', { name: '도장 손상 하자 상세 보기' })).toBeNull();

    for (const tabName of ['검수확정 1', '조치중 1', '조치완료 1', '전체 3']) {
      fireEvent.click(screen.getByRole('tab', { name: tabName }));
      expect(screen.queryByRole('button', { name: '도장 손상 하자 상세 보기' })).toBeNull();
    }
  });

  it('전체 탭 카운트는 DETECTED를 제외한 하자 수와 일치한다', () => {
    render(<DefectCardGrid defects={[...defects, detectedDefect]} onSelectDefect={vi.fn()} />);

    const tabs = screen.getByRole('tablist', { name: '상태 필터' });
    expect(within(tabs).getByRole('tab', { name: '전체 3' })).not.toBeNull();
    expect(within(tabs).queryByRole('tab', { name: '전체 4' })).toBeNull();
  });
});

describe('DefectCardGrid — 퍼넬(유형·등급) 필터', () => {
  it('퍼넬 버튼을 클릭하면 유형·등급 필터 패널이 열린다', () => {
    render(<DefectCardGrid defects={defects} onSelectDefect={vi.fn()} />);

    expect(screen.queryByLabelText('유형 필터')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '유형·등급 필터' }));

    expect(screen.getByLabelText('유형 필터')).not.toBeNull();
    expect(screen.getByLabelText('등급 필터')).not.toBeNull();
  });

  it('유형 필터를 선택하면 해당 유형 카드만 보여준다', () => {
    render(<DefectCardGrid defects={defects} onSelectDefect={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '유형·등급 필터' }));
    fireEvent.change(screen.getByLabelText('유형 필터'), { target: { value: 'REBAR_EXPOSURE' } });

    expect(screen.getByRole('button', { name: '철근 노출 하자 상세 보기' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: '균열 하자 상세 보기' })).toBeNull();
  });

  it('바깥 영역을 클릭하면 필터 패널이 닫힌다', () => {
    render(<DefectCardGrid defects={defects} onSelectDefect={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '유형·등급 필터' }));
    expect(screen.getByLabelText('유형 필터')).not.toBeNull();

    fireEvent.mouseDown(document.body);

    expect(screen.queryByLabelText('유형 필터')).toBeNull();
  });
});
