// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FacilityLocation } from '../types';
import { BackfillGeocodeButton } from './BackfillGeocodeButton';

const { getFacilityLocationsMock, useBackfillFacilityGeocodeMock, runMock } = vi.hoisted(() => ({
  getFacilityLocationsMock: vi.fn(),
  useBackfillFacilityGeocodeMock: vi.fn(),
  runMock: vi.fn(),
}));

// MAP_FACILITIES_QUERY_KEY는 실제 값을 그대로 써야(MapPage와 캐시 공유·invalidateQueries 검증이
// 의미 있음) getFacilityLocations(네트워크 호출)만 목으로 교체한다. 대상 산정을 지도 소스로
// 전환했으므로(#1657 P2) 여기서는 facility feature의 useFacilities가 아니라 mapApi를 목으로 삼는다.
vi.mock('../api/mapApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/mapApi')>();
  return {
    ...actual,
    mapApi: { ...actual.mapApi, getFacilityLocations: getFacilityLocationsMock },
  };
});

// needsBackfill(순수 함수)은 실제 구현을 그대로 쓰고, useBackfillFacilityGeocode(훅)만 목으로 교체한다.
vi.mock('../../facility/hooks/useBackfillFacilityGeocode', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../facility/hooks/useBackfillFacilityGeocode')>();
  return { ...actual, useBackfillFacilityGeocode: useBackfillFacilityGeocodeMock };
});

function makeFacilityLocation(overrides: Partial<FacilityLocation>): FacilityLocation {
  return {
    id: 1,
    name: '테스트 시설물',
    address: '서울 강남구 테헤란로 123',
    category: '건물',
    latitude: null,
    longitude: null,
    highestGrade: null,
    warningCount: null,
    cautionCount: null,
    thumbnailUrl: null,
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
  getFacilityLocationsMock.mockReset();
  useBackfillFacilityGeocodeMock.mockReset();
  runMock.mockReset();
});

describe('BackfillGeocodeButton', () => {
  it('좌표 없는 시설물이 없고 직전 실행 결과도 없으면 아무것도 렌더링하지 않는다', async () => {
    getFacilityLocationsMock.mockResolvedValue([
      makeFacilityLocation({ id: 1, latitude: 37.5, longitude: 127.0 }),
    ]);
    useBackfillFacilityGeocodeMock.mockReturnValue({ run: runMock, isRunning: false, lastResult: null });

    const { container } = renderButton();

    await waitFor(() => expect(getFacilityLocationsMock).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it('좌표 없는 시설물이 있으면 "좌표 없는 시설물 N건 일괄 보정" 버튼을 렌더링한다(지도 무상한 소스 기준, #1656/#1657 P2)', async () => {
    getFacilityLocationsMock.mockResolvedValue([
      makeFacilityLocation({ id: 1, latitude: null, longitude: null }),
      makeFacilityLocation({ id: 2, latitude: null, longitude: null }),
      makeFacilityLocation({ id: 3, latitude: 37.5, longitude: 127.0 }),
    ]);
    useBackfillFacilityGeocodeMock.mockReturnValue({ run: runMock, isRunning: false, lastResult: null });

    renderButton();

    expect(await screen.findByRole('button', { name: '좌표 없는 시설물 2건 일괄 보정' })).toBeTruthy();
  });

  it('버튼 클릭 시 지도 소스(mapApi) 목록 전체로 run을 호출하고, 완료 후 facility·map 쿼리를 모두 무효화한다', async () => {
    const facilities: FacilityLocation[] = [
      makeFacilityLocation({ id: 1, latitude: null, longitude: null }),
      makeFacilityLocation({ id: 2, latitude: 37.5, longitude: 127.0 }),
    ];
    getFacilityLocationsMock.mockResolvedValue(facilities);
    runMock.mockResolvedValue({ targetCount: 1, succeeded: 1, failures: [], skippedNoAddressCount: 0 });
    useBackfillFacilityGeocodeMock.mockReturnValue({ run: runMock, isRunning: false, lastResult: null });

    const { queryClient } = renderButton();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    fireEvent.click(await screen.findByRole('button', { name: '좌표 없는 시설물 1건 일괄 보정' }));

    await waitFor(() => expect(runMock).toHaveBeenCalledWith(facilities));
    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['facility', 'list'] }),
    );
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['map', 'facilities'] });
  });

  it('isRunning이 true면 버튼이 비활성화되고 "좌표 보정 중..." 문구를 표시한다', async () => {
    getFacilityLocationsMock.mockResolvedValue([
      makeFacilityLocation({ id: 1, latitude: null, longitude: null }),
    ]);
    useBackfillFacilityGeocodeMock.mockReturnValue({ run: runMock, isRunning: true, lastResult: null });

    renderButton();

    const button = await screen.findByRole('button', { name: '좌표 보정 중...' });
    expect(button).toBeTruthy();
    expect(button.hasAttribute('disabled')).toBe(true);
  });

  it('lastResult가 있으면 보정 완료/실패/제외 건수를 요약해 표시한다', async () => {
    getFacilityLocationsMock.mockResolvedValue([
      makeFacilityLocation({ id: 1, latitude: 37.5, longitude: 127.0 }),
    ]);
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

    expect(await screen.findByText('1건 보정 완료 · 실패 1건 · 주소 없어 제외 1건')).toBeTruthy();
    // 보정 대상(targetCount)이 0이라 버튼 자체는 더 이상 렌더링하지 않는다.
    expect(screen.queryByRole('button')).toBeNull();
  });
});
