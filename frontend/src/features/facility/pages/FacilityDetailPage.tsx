import { useNavigate, useSearchParams } from 'react-router-dom';
import { FacilityOverviewPanel } from '../../../shared/components/FacilityOverviewPanel';
import { InspectionCycleStatusBadge } from '../components/InspectionCycleStatusBadge';
import { useFacility } from '../hooks/useFacility';
import { useFacilityInspectionOverview } from '../hooks/useFacilityInspectionOverview';

// 라우트에 시설물 컨텍스트가 없어 쿼리파라미터(?facilityId=)로 대상 지정,
// 미지정 시 mockFacilities의 대표 행(강남 오피스타워 A동, id=1 — Figma 캡처와 이름이 일치)을 기본값으로 사용
// (InspectionCycleSettingsPage와 동일 패턴).
const DEFAULT_FACILITY_ID = 1;

// 시설물 상세 — Figma "hajaCheck Facility Detail - Fixed Images"(node-id 1-1401) dev mode 마크업 기준.
// 레이아웃은 features/inspection의 점검(회차) 생성 화면과 공유해 shared/FacilityOverviewPanel로 렌더링한다.
// 기본 정보(이름/유형/주소/준공년도/규모/다음 점검일)는 GET /api/facilities/{id} 실 연동,
// 점검 회차/누적 하자/미조치/점검 이력·등급은 집계 API가 아직 없어 feature 로컬 목(useFacilityInspectionOverview).
export function FacilityDetailPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const facilityId = Number(searchParams.get('facilityId')) || DEFAULT_FACILITY_ID;

  const { data: facility, isLoading, isError } = useFacility(facilityId);
  const { data: overview } = useFacilityInspectionOverview(facilityId);

  if (isLoading) {
    return <div className="px-8 py-8 text-base text-neutral-600">불러오는 중...</div>;
  }
  if (isError || !facility) {
    return (
      <div className="px-8 py-8 text-base text-neutral-600">시설물 정보를 불러오지 못했습니다.</div>
    );
  }

  const metaLine = [
    facility.type,
    facility.address,
    facility.builtYear && `준공 ${facility.builtYear}`,
    facility.scale,
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <FacilityOverviewPanel
      facilityName={facility.name}
      metaLine={metaLine}
      grade={overview?.overallGrade}
      totalRounds={overview?.totalRounds ?? '—'}
      cumulativeDefectCount={overview?.cumulativeDefectCount ?? '—'}
      unresolvedDefectCount={overview?.unresolvedDefectCount ?? '—'}
      nextInspectionBadge={<InspectionCycleStatusBadge nextInspectionDueAt={facility.nextInspectionDueAt} />}
      history={overview?.history ?? []}
      // 시설물 수정 API(PUT /api/facilities/{id})는 아직 이 화면에 배선되지 않음 — 후속 작업
      onNewInspection={() => navigate(`/inspections/create?facilityId=${facilityId}`)}
    />
  );
}
