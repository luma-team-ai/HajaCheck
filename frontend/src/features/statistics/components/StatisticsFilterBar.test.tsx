// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StatisticsFilterBar } from './StatisticsFilterBar';

afterEach(cleanup);

describe('StatisticsFilterBar', () => {
  it('기간 및 시설물 선택 요소와 내보내기 버튼이 정상 렌더링된다', () => {
    const onPeriodChange = vi.fn();
    const onFacilityChange = vi.fn();
    const onExport = vi.fn();

    render(
      <StatisticsFilterBar
        selectedPeriod="6m"
        onPeriodChange={onPeriodChange}
        selectedFacility="all"
        onFacilityChange={onFacilityChange}
        onExport={onExport}
      />,
    );

    const periodBtn = screen.getByRole('button', { name: '조회 기간 선택' });
    expect(periodBtn).toBeTruthy();
    expect(periodBtn.textContent).toContain('최근 6개월');

    const facilityBtn = screen.getByRole('button', { name: '시설물 범위 선택' });
    expect(facilityBtn).toBeTruthy();
    expect(facilityBtn.textContent).toContain('전체 시설물');

    const exportBtn = screen.getByRole('button', { name: /내보내기/i });
    expect(exportBtn).toBeTruthy();
  });

  it('기간 클릭 후 옵션 선택 시 onPeriodChange 콜백이 호출된다', () => {
    const onPeriodChange = vi.fn();
    const onFacilityChange = vi.fn();
    const onExport = vi.fn();

    render(
      <StatisticsFilterBar
        selectedPeriod="6m"
        onPeriodChange={onPeriodChange}
        selectedFacility="all"
        onFacilityChange={onFacilityChange}
        onExport={onExport}
      />,
    );

    const periodBtn = screen.getByRole('button', { name: '조회 기간 선택' });
    fireEvent.click(periodBtn);

    const option3m = screen.getByRole('option', { name: '최근 3개월' });
    fireEvent.click(option3m);

    expect(onPeriodChange).toHaveBeenCalledWith('3m');
  });

  it('내보내기 버튼 클릭 시 onExport 콜백이 호출된다', () => {
    const onPeriodChange = vi.fn();
    const onFacilityChange = vi.fn();
    const onExport = vi.fn();

    render(
      <StatisticsFilterBar
        selectedPeriod="6m"
        onPeriodChange={onPeriodChange}
        selectedFacility="all"
        onFacilityChange={onFacilityChange}
        onExport={onExport}
      />,
    );

    const exportBtn = screen.getByRole('button', { name: /내보내기/i });
    fireEvent.click(exportBtn);
    expect(onExport).toHaveBeenCalledTimes(1);
  });
});
