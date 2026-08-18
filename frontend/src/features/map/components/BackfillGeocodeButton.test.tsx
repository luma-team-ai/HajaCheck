// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Facility } from '../../facility/types';
import { BackfillGeocodeButton } from './BackfillGeocodeButton';

const { useFacilitiesMock, useBackfillFacilityGeocodeMock, runMock } = vi.hoisted(() => ({
  useFacilitiesMock: vi.fn(),
  useBackfillFacilityGeocodeMock: vi.fn(),
  runMock: vi.fn(),
}));

// facilityKeys는 실제 값을 그대로 써야 invalidateQueries 검증이 의미가 있다 — useFacilities만 목으로 교체.
vi.mock('../../facility/hooks/useFacilities', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../facility/hooks/useFacilities')>();
  return { ...actual, useFacilities: useFacilitiesMock };
});

// needsBackfill(순수 함수)은 실제 구현을 그대로 쓰고, useBackfillFacilityGeocode(훅)만 목으로 교체한다.
vi.mock('../../facility/hooks/useBackfillFacilityGeocode', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../facility/hooks/useBackfillFacilityGeocode')>();
  return { ...actual, useBackfillFacilityGeocode: useBackfillFacilityGeocodeMock };
});

function makeFacility(overrides: Partial<Facility>): Facility {
  return {
    id: 1,
    name: '테스트 시설물',
    type: '건물',
    address: '서울 강남구 테헤란로 123',
    latitude: null,
    longitude: null,
    builtYear: null,
    scale: null,
    inspectionCycleMonths: null,
    nextInspectionDueAt: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    initialGrade: null,
    assigneeUserId: null,
    memo: null,
    latestDefectId: null,
    thumbnailUrl: null,
    lastInspectedAt: null,
    defectCount: 0,
    ...overrides,
  };
}

function renderButton() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <BackfillGeocodeButton />
      </QueryClientProvider>,
    ),
  };
}

afterEach(() => {
  cleanup();
  useFacilitiesMock.mockReset();
  useBackfillFacilityGeocodeMock.mockReset();
  runMock.mockReset();
});

describe('BackfillGeocodeButton', () => {
  it('좌표 없는 시설물이 없고 직전 실행 결과도 없으면 아무것도 렌더링하지 않는다', () => {
    useFacilitiesMock.mockReturnValue({ data: [makeFacility({ id: 1, latitude: 37.5, longitude: 127.0 })] });
    useBackfillFacilityGeocodeMock.mockReturnValue({ run: runMock, isRunning: false, lastResult: null });

    const { container } = renderButton();

    expect(container.firstChild).toBeNull();
  });

  it('좌표 없는 시설물이 있으면 "좌표 없는 시설물 N건 일괄 보정" 버튼을 렌더링한다', () => {
    useFacilitiesMock.mockReturnValue({
      data: [
        makeFacility({ id: 1, latitude: null, longitude: null }),
        makeFacility({ id: 2, latitude: null, longitude: null }),
        makeFacility({ id: 3, latitude: 37.5, longitude: 127.0 }),
      ],
    });
    useBackfillFacilityGeocodeMock.mockReturnValue({ run: runMock, isRunning: false, lastResult: null });

    renderButton();

    expect(screen.getByRole('button', { name: '좌표 없는 시설물 2건 일괄 보정' })).toBeTruthy();
  });

  it('버튼 클릭 시 좌표 없는 시설물 전체 목록으로 run을 호출하고, 완료 후 facility·map 쿼리를 모두 무효화한다', async () => {
    const facilities = [
      makeFacility({ id: 1, latitude: null, longitude: null }),
      makeFacility({ id: 2, latitude: 37.5, longitude: 127.0 }),
    ];
    useFacilitiesMock.mockReturnValue({ data: facilities });
    runMock.mockResolvedValue({ targetCount: 1, succeeded: 1, failures: [], skippedNoAddressCount: 0 });
    useBackfillFacilityGeocodeMock.mockReturnValue({ run: runMock, isRunning: false, lastResult: null });

    const { queryClient } = renderButton();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    fireEvent.click(screen.getByRole('button', { name: '좌표 없는 시설물 1건 일괄 보정' }));

    await waitFor(() => expect(runMock).toHaveBeenCalledWith(facilities));
    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['facility', 'list'] }),
    );
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['map', 'facilities'] });
  });

  it('isRunning이 true면 버튼이 비활성화되고 "좌표 보정 중..." 문구를 표시한다', () => {
    useFacilitiesMock.mockReturnValue({
      data: [makeFacility({ id: 1, latitude: null, longitude: null })],
    });
    useBackfillFacilityGeocodeMock.mockReturnValue({ run: runMock, isRunning: true, lastResult: null });

    renderButton();

    const button = screen.getByRole('button', { name: '좌표 보정 중...' });
    expect(button).toBeTruthy();
    expect(button.hasAttribute('disabled')).toBe(true);
  });

  it('lastResult가 있으면 보정 완료/실패/제외 건수를 요약해 표시한다', () => {
    useFacilitiesMock.mockReturnValue({
      data: [makeFacility({ id: 1, latitude: 37.5, longitude: 127.0 })],
    });
    useBackfillFacilityGeocodeMock.mockReturnValue({
      run: runMock,
      isRunning: false,
      lastResult: {
        targetCount: 3,
        succeeded: 1,
        failures: [{ id: 2, name: '실패건', reason: '주소를 찾을 수 없습니다' }],
        skippedNoAddressCount: 1,
      },
    });

    renderButton();

    expect(screen.getByText('1건 보정 완료 · 실패 1건 · 주소 없어 제외 1건')).toBeTruthy();
    // 보정 대상(targetCount)이 0이라 버튼 자체는 더 이상 렌더링하지 않는다.
    expect(screen.queryByRole('button')).toBeNull();
  });
});
