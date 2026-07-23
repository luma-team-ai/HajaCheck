// @vitest-environment jsdom
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Facility } from '../types';

const { geocodeAddressMock, updateMock } = vi.hoisted(() => ({
  geocodeAddressMock: vi.fn(),
  updateMock: vi.fn(),
}));

vi.mock('../../../shared/lib/kakaoMap/geocodeAddress', async () => {
  const actual = await vi.importActual<
    typeof import('../../../shared/lib/kakaoMap/geocodeAddress')
  >('../../../shared/lib/kakaoMap/geocodeAddress');
  return {
    ...actual,
    geocodeAddress: geocodeAddressMock,
  };
});

vi.mock('../api/facilityApi', () => ({
  facilityApi: {
    update: updateMock,
  },
}));

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
    ...overrides,
  };
}

afterEach(() => {
  geocodeAddressMock.mockReset();
  updateMock.mockReset();
});

describe('useBackfillFacilityGeocode', () => {
  it('좌표가 이미 있는 시설물은 건너뛴다', async () => {
    const { useBackfillFacilityGeocode } = await import('./useBackfillFacilityGeocode');
    const { result } = renderHook(() => useBackfillFacilityGeocode());

    const facilities = [makeFacility({ id: 1, latitude: 37.5, longitude: 127.0 })];

    let outcome;
    await act(async () => {
      outcome = await result.current.run(facilities);
    });

    expect(geocodeAddressMock).not.toHaveBeenCalled();
    expect(updateMock).not.toHaveBeenCalled();
    expect(outcome).toEqual({
      targetCount: 0,
      succeeded: 0,
      failures: [],
      skippedNoAddressCount: 0,
    });
  });

  it('주소가 없는 좌표 미보유 시설물은 skippedNoAddressCount로 집계하고 시도하지 않는다', async () => {
    const { useBackfillFacilityGeocode } = await import('./useBackfillFacilityGeocode');
    const { result } = renderHook(() => useBackfillFacilityGeocode());

    const facilities = [makeFacility({ id: 1, address: null })];

    let outcome;
    await act(async () => {
      outcome = await result.current.run(facilities);
    });

    expect(geocodeAddressMock).not.toHaveBeenCalled();
    expect(outcome).toMatchObject({ targetCount: 0, skippedNoAddressCount: 1 });
  });

  it('좌표 없고 주소 있는 시설물은 geocode 후 update API로 반영한다', async () => {
    geocodeAddressMock.mockResolvedValue({ latitude: 37.5006, longitude: 127.0364 });
    updateMock.mockResolvedValue({ data: {} });

    const { useBackfillFacilityGeocode } = await import('./useBackfillFacilityGeocode');
    const { result } = renderHook(() => useBackfillFacilityGeocode());

    const facilities = [makeFacility({ id: 5, name: '수원 스마트팩토리' })];

    let outcome;
    await act(async () => {
      outcome = await result.current.run(facilities);
    });

    expect(geocodeAddressMock).toHaveBeenCalledWith('서울 강남구 테헤란로 123');
    expect(updateMock).toHaveBeenCalledWith(
      5,
      expect.objectContaining({ latitude: 37.5006, longitude: 127.0364, name: '수원 스마트팩토리' }),
    );
    expect(outcome).toMatchObject({ targetCount: 1, succeeded: 1, failures: [] });
  });

  it('geocode 실패 건은 failures에 기록하고 나머지는 계속 처리한다', async () => {
    geocodeAddressMock
      .mockRejectedValueOnce(new Error('주소를 찾을 수 없습니다'))
      .mockResolvedValueOnce({ latitude: 37.4, longitude: 127.1 });
    updateMock.mockResolvedValue({ data: {} });

    const { useBackfillFacilityGeocode } = await import('./useBackfillFacilityGeocode');
    const { result } = renderHook(() => useBackfillFacilityGeocode());

    const facilities = [
      makeFacility({ id: 1, name: '실패건' }),
      makeFacility({ id: 2, name: '성공건' }),
    ];

    let outcome;
    await act(async () => {
      outcome = await result.current.run(facilities);
    });

    expect(updateMock).toHaveBeenCalledTimes(1);
    expect(outcome).toMatchObject({
      targetCount: 2,
      succeeded: 1,
      failures: [{ id: 1, name: '실패건', reason: '주소를 찾을 수 없습니다' }],
    });
  });

  it('실행 중에는 isRunning이 true다', async () => {
    let resolveGeocode: (value: { latitude: number; longitude: number }) => void = () => {};
    geocodeAddressMock.mockReturnValue(
      new Promise((resolve) => {
        resolveGeocode = resolve;
      }),
    );
    updateMock.mockResolvedValue({ data: {} });

    const { useBackfillFacilityGeocode } = await import('./useBackfillFacilityGeocode');
    const { result } = renderHook(() => useBackfillFacilityGeocode());

    const facilities = [makeFacility({ id: 1 })];

    let runPromise: Promise<unknown>;
    act(() => {
      runPromise = result.current.run(facilities);
    });

    await waitFor(() => expect(result.current.isRunning).toBe(true));

    await act(async () => {
      resolveGeocode({ latitude: 1, longitude: 2 });
      await runPromise;
    });

    expect(result.current.isRunning).toBe(false);
  });
});
