// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./loadKakaoMapSdk', () => ({
  loadKakaoMapSdk: vi.fn().mockResolvedValue(undefined),
}));

const OK = 'OK' as unknown as string;
const ZERO_RESULT = 'ZERO_RESULT' as unknown as string;
const ERROR = 'ERROR' as unknown as string;

function stubKakaoGeocoder(
  implementation: (
    address: string,
    callback: (result: unknown[], status: string) => void,
  ) => void,
) {
  // `new window.kakao.maps.services.Geocoder()`로 호출되므로 화살표 함수는 생성자로 쓸 수 없다
  // (vi.fn().mockImplementation(() => ...)는 TypeError: not a constructor) — function 표현식 사용.
  function GeocoderStub(this: { addressSearch: typeof implementation }) {
    this.addressSearch = implementation;
  }

  (window as unknown as { kakao: unknown }).kakao = {
    maps: {
      services: {
        Geocoder: vi.fn().mockImplementation(GeocoderStub),
        Status: { OK, ZERO_RESULT, ERROR },
      },
    },
  };
}

describe('geocodeAddress', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete (window as unknown as { kakao?: unknown }).kakao;
  });

  it('빈 문자열 주소는 GeocodeNotFoundError로 reject한다(SDK 호출 없이)', async () => {
    const { geocodeAddress, GeocodeNotFoundError } = await import('./geocodeAddress');

    await expect(geocodeAddress('   ')).rejects.toBeInstanceOf(GeocodeNotFoundError);
  });

  it('검색 성공 시 위경도를 숫자로 반환한다', async () => {
    stubKakaoGeocoder((_address, callback) => {
      callback([{ x: '127.0364', y: '37.5006', address_name: '서울 강남구 테헤란로 123' }], OK);
    });

    const { geocodeAddress } = await import('./geocodeAddress');

    await expect(geocodeAddress('서울 강남구 테헤란로 123')).resolves.toEqual({
      latitude: 37.5006,
      longitude: 127.0364,
    });
  });

  it('결과 없음(ZERO_RESULT)이면 GeocodeNotFoundError로 reject한다', async () => {
    stubKakaoGeocoder((_address, callback) => {
      callback([], ZERO_RESULT);
    });

    const { geocodeAddress, GeocodeNotFoundError } = await import('./geocodeAddress');

    await expect(geocodeAddress('존재하지 않는 주소 xyz')).rejects.toBeInstanceOf(
      GeocodeNotFoundError,
    );
  });

  it('그 외 실패(ERROR)면 GeocodeFailedError로 reject한다', async () => {
    stubKakaoGeocoder((_address, callback) => {
      callback([], ERROR);
    });

    const { geocodeAddress, GeocodeFailedError } = await import('./geocodeAddress');

    await expect(geocodeAddress('서울 강남구 테헤란로 123')).rejects.toBeInstanceOf(
      GeocodeFailedError,
    );
  });
});
