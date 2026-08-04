// 주소 문자열 → 위경도 변환 (Kakao Maps Geocoder 래퍼, #618)
// window.kakao.maps.services.Geocoder().addressSearch 콜백 API를 Promise화한다.
// 실패/결과없음 케이스를 조용히 삼키지 않고 에러로 표면화한다(이 프로젝트 리뷰 기준 P2 대상 — CLAUDE.md).
import { KakaoMapSdkTimeoutError, loadKakaoMapSdk } from './loadKakaoMapSdk';

export interface GeocodeResult {
  latitude: number;
  longitude: number;
}

/** 주소로 검색했으나 일치하는 결과가 없을 때(카카오 Status.ZERO_RESULT) */
export class GeocodeNotFoundError extends Error {
  constructor(address: string) {
    super(`주소를 찾을 수 없습니다: "${address}". 주소를 더 구체적으로 입력해 주세요.`);
    this.name = 'GeocodeNotFoundError';
  }
}

/** Geocoder 자체가 실패했을 때(카카오 Status.ERROR, 네트워크 오류 등) */
export class GeocodeFailedError extends Error {
  constructor(address: string) {
    super(`주소 좌표 변환에 실패했습니다: "${address}". 잠시 후 다시 시도해 주세요.`);
    this.name = 'GeocodeFailedError';
  }
}

/**
 * 주소 문자열을 위경도로 변환한다.
 * SDK가 아직 로드되지 않았으면 loadKakaoMapSdk()를 먼저 기다린다(libraries=services 필요).
 * 결과가 없으면 GeocodeNotFoundError, 그 외 실패는 GeocodeFailedError로 reject한다(에러 삼키지 않음).
 */
export async function geocodeAddress(address: string): Promise<GeocodeResult> {
  const trimmed = address.trim();
  if (!trimmed) {
    return Promise.reject(new GeocodeNotFoundError(address));
  }

  try {
    await loadKakaoMapSdk();
  } catch (error) {
    // SDK 스크립트가 응답 없이 매달린 경우(타임아웃)는 "좌표 변환 실패"로 표면화한다 —
    // 주소 필수화(#1546) 이후 모든 시설물 등록이 이 경로를 타므로, 여기서 영구 pending되면
    // 호출부(FacilityFormModal)의 best-effort catch(#629)가 실행되지 못해 등록 모달이
    // "등록 중"에서 멈춘다(#1590 P2). GeocodeFailedError로 바꿔 기존 실패 경로로 흘려보낸다.
    if (error instanceof KakaoMapSdkTimeoutError) {
      throw new GeocodeFailedError(trimmed);
    }
    throw error;
  }

  return new Promise<GeocodeResult>((resolve, reject) => {
    const geocoder = new window.kakao.maps.services.Geocoder();
    geocoder.addressSearch(trimmed, (result, status) => {
      if (status === window.kakao.maps.services.Status.OK && result[0]) {
        resolve({
          latitude: Number(result[0].y),
          longitude: Number(result[0].x),
        });
        return;
      }
      if (status === window.kakao.maps.services.Status.ZERO_RESULT) {
        reject(new GeocodeNotFoundError(trimmed));
        return;
      }
      reject(new GeocodeFailedError(trimmed));
    });
  });
}
