import { useCallback, useState } from 'react';
import {
  GeocodeFailedError,
  GeocodeNotFoundError,
  geocodeAddress,
} from '../../../shared/lib/kakaoMap/geocodeAddress';
import { facilityApi } from '../api/facilityApi';
import type { Facility } from '../types';

export interface BackfillFailure {
  id: number;
  name: string;
  reason: string;
}

export interface BackfillResult {
  /** 좌표가 없고 주소가 있어 재계산을 시도한 시설물 수 */
  targetCount: number;
  succeeded: number;
  failures: BackfillFailure[];
  /** 좌표가 없지만 주소도 없어 애초에 재계산을 시도할 수 없었던 시설물 수 */
  skippedNoAddressCount: number;
}

// 좌표(latitude/longitude)가 하나라도 비어 있으면 재계산 대상으로 취급한다(#618 — PM 확정: "NULL이거나
// 부정확한 것으로 판단되는 건" 중 자동 판별 가능한 범위는 NULL). 주소가 없는 시설물은 애초에 좌표를
// 계산할 방법이 없으므로 대상에서 제외하고 결과에 별도 카운트로 보고한다.
function needsBackfill(facility: Facility): boolean {
  return facility.latitude == null || facility.longitude == null;
}

/**
 * 좌표가 없는 시설물을 순회하며 프론트에 이미 로드된 Kakao Geocoder로 좌표를 계산하고,
 * 기존 PUT /api/facilities/{id} API로 반영한다(관리자용 일괄 재-geocoding, #618).
 * 요청을 순차 처리해 Geocoder/서버에 과도한 동시 요청을 보내지 않는다.
 */
export function useBackfillFacilityGeocode() {
  const [isRunning, setIsRunning] = useState(false);
  const [lastResult, setLastResult] = useState<BackfillResult | null>(null);

  const run = useCallback(async (facilities: Facility[]): Promise<BackfillResult> => {
    setIsRunning(true);
    try {
      const withoutCoords = facilities.filter(needsBackfill);
      const targets = withoutCoords.filter((facility) => facility.address?.trim());
      const skippedNoAddressCount = withoutCoords.length - targets.length;

      let succeeded = 0;
      const failures: BackfillFailure[] = [];

      for (const facility of targets) {
        try {
          // address는 filter에서 trim() 확인을 거쳤으므로 non-null 단언 대신 as string으로
          // 타입만 좁힌다(값 자체는 이미 검증됨).
          const { latitude, longitude } = await geocodeAddress(facility.address as string);

          // lost update 방지(#638, PR #631 P2 후속): 배치는 시설물마다 Geocoder 호출 → PUT이라
          // 시간이 걸리고, 백엔드 Facility 엔티티에 낙관적 락(@Version)이 없다. 실행 시작 시점의
          // 스냅샷(facility)을 그대로 PUT 바디에 담아 보내면, 그 사이 다른 세션이 이 시설물의 다른
          // 필드를 수정한 경우 이 배치가 좌표만 바꾸려다 그 수정사항을 조용히 덮어써 유실시킨다.
          // PUT 직전 최신 레코드를 재조회해 그 위에 새로 계산한 좌표만 병합해서 보낸다.
          const { data: latest } = await facilityApi.getDetail(facility.id);

          // 주소 정합성 검증(#672, PR #641 P2 후속): geocode 계산은 배치 시작 시점 스냅샷
          // (facility.address)을 기준으로 했다. PUT 직전 재조회한 latest.address가 그 사이
          // 바뀌었다면, 이번에 계산한 좌표는 이미 낡은 주소 기준이라 "최신 주소 + 옛 주소 기준
          // 좌표"라는 정합성이 깨진 레코드가 저장될 수 있다. 이 경우 PUT을 건너뛰고 실패로
          // 기록한다.
          if (latest.address !== facility.address) {
            failures.push({
              id: facility.id,
              name: facility.name,
              reason: '배치 중 주소가 변경되어 좌표 재계산이 필요합니다.',
            });
            continue;
          }

          await facilityApi.update(facility.id, {
            name: latest.name,
            type: latest.type,
            address: latest.address,
            latitude,
            longitude,
            builtYear: latest.builtYear,
            scale: latest.scale,
            inspectionCycleMonths: latest.inspectionCycleMonths,
            nextInspectionDueAt: latest.nextInspectionDueAt,
            // #628(HAJA-347) 등록 필드 확장 — PUT은 전체 교체라 여기서도 최신 값을 그대로 실어야
            // 좌표만 갱신하려다 초기등급/담당자/메모를 조용히 null로 덮어쓰는 lost update를 막는다.
            initialGrade: latest.initialGrade,
            assigneeUserId: latest.assigneeUserId,
            memo: latest.memo,
          });
          succeeded += 1;
        } catch (error) {
          const reason =
            error instanceof GeocodeNotFoundError || error instanceof GeocodeFailedError
              ? error.message
              : error instanceof Error
                ? error.message
                : '알 수 없는 오류로 좌표 재계산에 실패했습니다.';
          failures.push({ id: facility.id, name: facility.name, reason });
        }
      }

      const result: BackfillResult = {
        targetCount: targets.length,
        succeeded,
        failures,
        skippedNoAddressCount,
      };
      setLastResult(result);
      return result;
    } finally {
      setIsRunning(false);
    }
  }, []);

  return { run, isRunning, lastResult };
}
