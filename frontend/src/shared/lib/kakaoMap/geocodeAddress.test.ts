// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 에러 클래스(KakaoMapSdkTimeoutError 등)는 실제 구현을 그대로 쓰고 로더 함수만 대체한다 —
// instanceof 판정(#1590 타임아웃 → GeocodeFailedError 변환)이 목 객체로 깨지지 않게 하기 위함.
const { loadKakaoMapSdkMock } = vi.hoisted(() => ({
  loadKakaoMapSdkMock: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('./loadKakaoMapSdk', async () => {
  const actual = await vi.importActual<typeof import('./loadKakaoMapSdk')>('./loadKakaoMapSdk');
  return { ...actual, loadKakaoMapSdk: loadKakaoMapSdkMock };
});

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

  // #1590 P2 — SDK 로드가 타임아웃되면 호출부의 best-effort 실패 경로(#629)를 그대로 타야 한다.
  it('SDK 로드 타임아웃이면 GeocodeFailedError로 reject한다', async () => {
    const { KakaoMapSdkTimeoutError } = await import('./loadKakaoMapSdk');
    loadKakaoMapSdkMock.mockRejectedValueOnce(new KakaoMapSdkTimeoutError());

    const { geocodeAddress, GeocodeFailedError } = await import('./geocodeAddress');

    await expect(geocodeAddress('서울 강남구 테헤란로 123')).rejects.toBeInstanceOf(
      GeocodeFailedError,
    );
  });

  it('타임아웃이 아닌 SDK 로드 실패(키 미설정 등)는 원래 에러 그대로 전파한다', async () => {
    const { KakaoMapKeyMissingError } = await import('./loadKakaoMapSdk');
    loadKakaoMapSdkMock.mockRejectedValueOnce(new KakaoMapKeyMissingError());

    const { geocodeAddress } = await import('./geocodeAddress');

    await expect(geocodeAddress('서울 강남구 테헤란로 123')).rejects.toBeInstanceOf(
      KakaoMapKeyMissingError,
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
