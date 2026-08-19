// @vitest-environment jsdom
// InspectionKpiSummary 단위 테스트 — Figma 정렬(#966, #937/PR#950 diff 누락분 보완)로 추가된
// 상태별 색상 dot과, 4종 전체에 붙는 "건" 단위 표기를 검증한다(#969 Header Panel 정렬로 "총 하자"도
// 단위를 표기하도록 변경). "총 하자"는 dot 없이 유지되는지도 확인.
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Defect } from '../types';
import { InspectionKpiSummary } from './InspectionKpiSummary';

afterEach(() => cleanup());

function makeDefect(overrides: Partial<Defect> & Pick<Defect, 'id' | 'status'>): Defect {
  return {
    inspectionId: 101,
    facilityId: 1,
    facilityName: '강남 오피스타워 A동',
    facilityType: '건물',
    type: 'CRACK',
    typeLabel: '균열',
    grade: 'C',
    confidence: 0.8,
    reviewed: true,
    bboxX: null,
    bboxY: null,
    bboxW: null,
    bboxH: null,
    crackWidthMm: null,
    crackLengthMm: null,
    areaMm2: null,
    imageUrl: null,
    createdAt: '2026-07-01T09:00:00.000Z',
    ...overrides,
  };
}

const defects: Defect[] = [
  makeDefect({ id: 1, status: 'CONFIRMED' }),
  makeDefect({ id: 2, status: 'IN_PROGRESS' }),
  makeDefect({ id: 3, status: 'IN_PROGRESS' }),
  makeDefect({ id: 4, status: 'RESOLVED' }),
  makeDefect({ id: 5, status: 'DETECTED' }),
];

describe('InspectionKpiSummary', () => {
  it('상태별 건수를 집계해 각 카드에 표시한다', () => {
    render(<InspectionKpiSummary defects={defects} />);

    const kpi = screen.getByLabelText('점검 하자 요약');
    expect(within(kpi).getByText('총 하자')).not.toBeNull();
    expect(within(kpi).getByText('5건')).not.toBeNull();
  });

  it('4종 항목 모두의 값에 "건" 단위를 붙인다', () => {
    render(<InspectionKpiSummary defects={defects} />);

    const kpi = screen.getByLabelText('점검 하자 요약');
    const terms = within(kpi).getAllByRole('term');

    function ddTextOf(label: string): string | null | undefined {
      const dt = terms.find((term) => term.textContent?.includes(label));
      return dt?.nextElementSibling?.textContent;
    }

    expect(ddTextOf('총 하자')).toBe('5건');
    expect(ddTextOf('검수확정')).toBe('1건');
    expect(ddTextOf('조치중')).toBe('2건');
    expect(ddTextOf('조치완료')).toBe('1건');
  });

  it('검수확정/조치중/조치완료 항목에는 상태별 색상 dot을 렌더링하고, 총 하자에는 렌더링하지 않는다', () => {
    render(<InspectionKpiSummary defects={defects} />);

    const kpi = screen.getByLabelText('점검 하자 요약');
    const cards = within(kpi).getAllByRole('term');

    const totalCard = cards.find((dt) => dt.textContent?.includes('총 하자'));
    const confirmedCard = cards.find((dt) => dt.textContent?.includes('검수확정'));
    const inProgressCard = cards.find((dt) => dt.textContent?.includes('조치중'));
    const resolvedCard = cards.find((dt) => dt.textContent?.includes('조치완료'));

    expect(totalCard?.querySelector('.inspection-kpi-summary__dot')).toBeNull();
    expect(confirmedCard?.querySelector('.inspection-kpi-summary__dot')).not.toBeNull();
    expect(inProgressCard?.querySelector('.inspection-kpi-summary__dot')).not.toBeNull();
    expect(resolvedCard?.querySelector('.inspection-kpi-summary__dot')).not.toBeNull();

    // dot은 STATUS_PRESENTATION의 text-* 색상 클래스를 재사용한다(신규 색상 상수 추가 금지 컨벤션)
    expect(
      confirmedCard?.querySelector('.inspection-kpi-summary__dot')?.className,
    ).toContain('text-zinc-700');
    expect(
      inProgressCard?.querySelector('.inspection-kpi-summary__dot')?.className,
    ).toContain('text-orange-500');
    expect(
      resolvedCard?.querySelector('.inspection-kpi-summary__dot')?.className,
    ).toContain('text-emerald-600');
  });

  // #1693 — 점검 상세 헤더의 점검 상태(InspectionStatus) 배지와 이 KPI(하자 조치 상태 집계)가
  // 같은 값으로 오인되지 않도록, 그룹 라벨 "하자 조치 현황"을 시각 + 접근성(role=group +
  // aria-labelledby) 둘 다로 노출하는지 검증한다.
  it('"하자 조치 현황" 그룹 라벨을 렌더링하고 KPI dl과 접근성으로 연결한다', () => {
    render(<InspectionKpiSummary defects={defects} />);

    const group = screen.getByRole('group', { name: '하자 조치 현황' });
    expect(within(group).getByText('하자 조치 현황')).not.toBeNull();
    // 그룹 안에 기존 KPI dl(aria-label="점검 하자 요약")이 그대로 포함돼 있어야 한다 —
    // 집계 로직/기존 접근성 라벨을 건드리지 않았는지 확인.
    expect(within(group).getByLabelText('점검 하자 요약')).not.toBeNull();
  });
});
