// 좌표 없는 시설물 일괄 보정 버튼(#1657 ③) — 지도 화면 목록 패널 상단에 배선한다. 백필 로직
// (useBackfillFacilityGeocode, #618)은 이전에 어느 화면에도 연결되지 않은 죽은 코드였다.
//
// facility feature에서 cross-feature import — React_코드_컨벤션.md §1은 feature 간 직접 import를
// 금지하지만, 이 레포에는 이미 report↔inspection, facility↔defect 같은 선례가 있다(예:
// features/report/pages/ReportEntryPage.tsx가 features/inspection 훅/스토어를 직접 import).
// 백필은 시설물 전체 필드(주소·유형 등, PUT 바디 구성에 필요)가 있어야 동작하는데, 지도 화면은
// 경량 프로젝션 타입(FacilityLocation, GET /api/facilities/map)만 갖고 있어 자체적으로는 수행할 수
// 없다 — facility feature의 Facility 전체 목록(useFacilities)과 백필 훅을 그대로 재사용한다.
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '../../../shared/components/Button';
import { needsBackfill, useBackfillFacilityGeocode } from '../../facility/hooks/useBackfillFacilityGeocode';
import { facilityKeys, useFacilities } from '../../facility/hooks/useFacilities';
import { MAP_FACILITIES_QUERY_KEY } from '../api/mapApi';

export function BackfillGeocodeButton() {
  const queryClient = useQueryClient();
  const { data: facilities } = useFacilities();
  const { run, isRunning, lastResult } = useBackfillFacilityGeocode();

  const targetCount = facilities?.filter(needsBackfill).length ?? 0;

  const handleClick = async () => {
    if (!facilities) return;
    await run(facilities);
    // 보정 결과(좌표 갱신)를 시설물 목록(facility feature)과 지도 목록(map feature) 양쪽에 반영한다 —
    // 둘 다 각자 쿼리 캐시를 들고 있어 한쪽만 무효화하면 다른 화면은 갱신 전 좌표를 계속 보여준다.
    queryClient.invalidateQueries({ queryKey: facilityKeys.list });
    queryClient.invalidateQueries({ queryKey: MAP_FACILITIES_QUERY_KEY });
  };

  // 보정할 대상이 없고, 직전 실행 결과도 없으면(아직 한 번도 실행 안 함) 아무것도 표시하지 않는다 —
  // 지도 화면에 불필요한 UI 잡음을 남기지 않기 위함.
  if (targetCount === 0 && !lastResult) {
    return null;
  }

  return (
    <div className="flex flex-col gap-1.5">
      {targetCount > 0 && (
        <Button type="button" variant="secondary" size="sm" onClick={handleClick} disabled={isRunning}>
          {isRunning ? '좌표 보정 중...' : `좌표 없는 시설물 ${targetCount}건 일괄 보정`}
        </Button>
      )}
      {lastResult && (
        <p role="status" className="m-0 text-[11px] text-text-muted">
          {lastResult.succeeded}건 보정 완료
          {lastResult.failures.length > 0 && ` · 실패 ${lastResult.failures.length}건`}
          {lastResult.skippedNoAddressCount > 0 && ` · 주소 없어 제외 ${lastResult.skippedNoAddressCount}건`}
        </p>
      )}
    </div>
  );
}
