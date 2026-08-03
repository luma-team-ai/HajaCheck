// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DefectTypeDistributionCard } from './DefectTypeDistributionCard';
import { FacilitySummaryTable } from './FacilitySummaryTable';
import { FacilityTypeHeatmap } from './FacilityTypeHeatmap';
import { MonthlyTrendCard } from './MonthlyTrendCard';
import { StatisticsGradeDistributionCard } from './StatisticsGradeDistributionCard';
import { StatisticsKpiSection } from './StatisticsKpiSection';

vi.mock('../hooks/useStatisticsGradeDistribution', () => ({
  useStatisticsGradeDistribution: () => ({ data: undefined, isLoading: false, isError: true }),
}));

vi.mock('../hooks/useFacilitySummary', () => ({
  useFacilitySummary: () => ({ data: undefined, isLoading: false, isError: true }),
}));

vi.mock('../hooks/useDefectTypeDistribution', () => ({
  useDefectTypeDistribution: () => ({ data: undefined, isLoading: false, isError: true }),
}));

vi.mock('../hooks/useMonthlyDefectTrend', () => ({
  useMonthlyDefectTrend: () => ({ data: undefined, isLoading: false, isError: true }),
}));

vi.mock('../hooks/useFacilityTypeHeatmap', () => ({
  useFacilityTypeHeatmap: () => ({ data: undefined, isLoading: false, isError: true }),
}));

vi.mock('../hooks/useStatisticsSummary', () => ({
  useStatisticsSummary: () => ({ data: undefined, isLoading: false, isError: true }),
}));

afterEach(() => cleanup());

describe('Statistics Cards — Error state rendering', () => {
  it('StatisticsGradeDistributionCard 에러 문구가 카드 중앙 영역에 정상 렌더링된다', () => {
    render(<StatisticsGradeDistributionCard />);
    const msg = screen.getByText('등급별 분포를 불러오지 못했습니다.');
    expect(msg).toBeTruthy();
    expect(msg.parentElement?.className).toContain('items-center');
    expect(msg.parentElement?.className).toContain('justify-center');
  });

  it('FacilitySummaryTable 에러 문구가 카드 중앙 영역에 정상 렌더링된다', () => {
    render(<FacilitySummaryTable />);
    const msg = screen.getByText('시설물별 요약을 불러오지 못했습니다.');
    expect(msg).toBeTruthy();
    expect(msg.parentElement?.className).toContain('items-center');
    expect(msg.parentElement?.className).toContain('justify-center');
  });

  it('DefectTypeDistributionCard 에러 문구가 카드 중앙 영역에 정상 렌더링된다', () => {
    render(<DefectTypeDistributionCard />);
    const msg = screen.getByText('AI 탐지 유형별 분포를 불러오지 못했습니다.');
    expect(msg).toBeTruthy();
    expect(msg.parentElement?.className).toContain('items-center');
  });

  it('MonthlyTrendCard 에러 문구가 카드 중앙 영역에 정상 렌더링된다', () => {
    render(<MonthlyTrendCard />);
    const msg = screen.getByText('월별 추이를 불러오지 못했습니다.');
    expect(msg).toBeTruthy();
    expect(msg.parentElement?.className).toContain('items-center');
  });

  it('FacilityTypeHeatmap 에러 문구가 카드 중앙 영역에 정상 렌더링된다', () => {
    render(<FacilityTypeHeatmap />);
    const msg = screen.getByText('히트맵 데이터를 불러오지 못했습니다.');
    expect(msg).toBeTruthy();
    expect(msg.parentElement?.className).toContain('items-center');
  });

  it('StatisticsKpiSection 에러 문구가 중앙 카드로 정상 렌더링된다', () => {
    render(<StatisticsKpiSection />);
    const msg = screen.getByText('요약 정보를 불러오지 못했습니다.');
    expect(msg).toBeTruthy();
    expect(msg.parentElement?.className).toContain('items-center');
  });
});
