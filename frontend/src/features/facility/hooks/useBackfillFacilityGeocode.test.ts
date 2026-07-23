// @vitest-environment jsdom
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Facility } from '../types';

const { geocodeAddressMock, updateMock, getDetailMock } = vi.hoisted(() => ({
  geocodeAddressMock: vi.fn(),
  updateMock: vi.fn(),
  getDetailMock: vi.fn(),
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
    getDetail: getDetailMock,
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
  getDetailMock.mockReset();
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
    getDetailMock.mockResolvedValue({ data: makeFacility({ id: 5, name: '수원 스마트팩토리' }) });
    updateMock.mockResolvedValue({ data: {} });

    const { useBackfillFacilityGeocode } = await import('./useBackfillFacilityGeocode');
    const { result } = renderHook(() => useBackfillFacilityGeocode());

    const facilities = [makeFacility({ id: 5, name: '수원 스마트팩토리' })];

    let outcome;
    await act(async () => {
      outcome = await result.current.run(facilities);
    });

    expect(geocodeAddressMock).toHaveBeenCalledWith('서울 강남구 테헤란로 123');
    expect(getDetailMock).toHaveBeenCalledWith(5);
    expect(updateMock).toHaveBeenCalledWith(
      5,
      expect.objectContaining({ latitude: 37.5006, longitude: 127.0364, name: '수원 스마트팩토리' }),
    );
    expect(outcome).toMatchObject({ targetCount: 1, succeeded: 1, failures: [] });
  });

  it('PUT 직전 최신 레코드를 재조회해 좌표만 병합하고, 나머지 필드는 배치 시작 시점 스냅샷이 아닌 최신값을 사용한다(lost update 방지, #638)', async () => {
    // 배치 시작 시점 스냅샷(facilities에 넘겨준 값)은 옛 이름 "구 시설물명"이었지만, Geocoder 호출이
    // 진행되는 동안 다른 세션이 이름을 "신 시설물명"으로 바꿨다고 가정한다. PUT 바디는 재조회한
    // 최신 이름을 담아야 하며(스냅샷의 옛 이름을 덮어써 유실시키면 안 됨), 좌표만 이번에 새로 계산된 값을 써야 한다.
    geocodeAddressMock.mockResolvedValue({ latitude: 37.7, longitude: 127.2 });
    getDetailMock.mockResolvedValue({
      data: makeFacility({
        id: 7,
        name: '신 시설물명',
        type: '교량',
        scale: '대형',
        inspectionCycleMonths: 12,
        latitude: null,
        longitude: null,
      }),
    });
    updateMock.mockResolvedValue({ data: {} });

    const { useBackfillFacilityGeocode } = await import('./useBackfillFacilityGeocode');
    const { result } = renderHook(() => useBackfillFacilityGeocode());

    const staleSnapshot = [makeFacility({ id: 7, name: '구 시설물명', type: '건물', scale: '소형' })];

    await act(async () => {
      await result.current.run(staleSnapshot);
    });

    expect(getDetailMock).toHaveBeenCalledWith(7);
    expect(updateMock).toHaveBeenCalledWith(
      7,
      expect.objectContaining({
        name: '신 시설물명',
        type: '교량',
        scale: '대형',
        inspectionCycleMonths: 12,
        latitude: 37.7,
        longitude: 127.2,
      }),
    );
  });

  it('geocode 실패 건은 failures에 기록하고 나머지는 계속 처리한다', async () => {
    geocodeAddressMock
      .mockRejectedValueOnce(new Error('주소를 찾을 수 없습니다'))
      .mockResolvedValueOnce({ latitude: 37.4, longitude: 127.1 });
    getDetailMock.mockResolvedValue({ data: makeFacility({ id: 2, name: '성공건' }) });
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

  it('PUT 직전 최신 레코드 재조회(getDetail)가 실패(예: 배치 중 삭제됨)하면 해당 건은 failures에 기록하고 나머지는 계속 처리한다', async () => {
    geocodeAddressMock.mockResolvedValue({ latitude: 37.4, longitude: 127.1 });
    getDetailMock
      .mockRejectedValueOnce(new Error('시설물을 찾을 수 없습니다'))
      .mockResolvedValueOnce({ data: makeFacility({ id: 2, name: '성공건' }) });
    updateMock.mockResolvedValue({ data: {} });

    const { useBackfillFacilityGeocode } = await import('./useBackfillFacilityGeocode');
    const { result } = renderHook(() => useBackfillFacilityGeocode());

    const facilities = [
      makeFacility({ id: 1, name: '배치 중 삭제됨' }),
      makeFacility({ id: 2, name: '성공건' }),
    ];

    let outcome;
    await act(async () => {
      outcome = await result.current.run(facilities);
    });

    expect(updateMock).toHaveBeenCalledTimes(1);
    expect(updateMock).toHaveBeenCalledWith(2, expect.objectContaining({ name: '성공건' }));
    expect(outcome).toMatchObject({
      targetCount: 2,
      succeeded: 1,
      failures: [{ id: 1, name: '배치 중 삭제됨', reason: '시설물을 찾을 수 없습니다' }],
    });
  });

  it('실행 중에는 isRunning이 true다', async () => {
    let resolveGeocode: (value: { latitude: number; longitude: number }) => void = () => {};
    geocodeAddressMock.mockReturnValue(
      new Promise((resolve) => {
        resolveGeocode = resolve;
      }),
    );
    getDetailMock.mockResolvedValue({ data: makeFacility({ id: 1 }) });
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
