import { useNavigate, useParams } from 'react-router-dom';
import { FacilityOverviewPanel } from '../../../shared/components/FacilityOverviewPanel';
import { InspectionCycleStatusBadge } from '../components/InspectionCycleStatusBadge';
import { useFacility } from '../hooks/useFacility';
import { useFacilityInspectionOverview } from '../hooks/useFacilityInspectionOverview';

// 시설물 상세 — Figma "hajaCheck Facility Detail - Fixed Images"(node-id 1-1401) dev mode 마크업 기준.
// 레이아웃은 features/inspection의 점검(회차) 생성 화면과 공유해 shared/FacilityOverviewPanel로 렌더링한다.
// 기본 정보(이름/유형/주소/준공년도/규모/다음 점검일)는 GET /api/facilities/{id} 실 연동,
// 점검 회차/누적 하자/미조치/점검 이력·등급은 집계 API가 아직 없어 feature 로컬 목(useFacilityInspectionOverview).
export function FacilityDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const facilityId = Number(id);
  const isInvalidId = Number.isNaN(facilityId);

  // NaN(예: 사이드바 플레이스홀더 '/facilities/detail')이면 useFacility 내부 enabled 가드로 조회
  // 자체를 시도하지 않는다(useInspectionResult와 동일 패턴) — 아래서 !facility로 에러 처리된다.
  const { data: facility, isLoading, isError } = useFacility(facilityId);
  const { data: overview } = useFacilityInspectionOverview(facilityId);

  if (isLoading) {
    return <div className="px-8 py-8 text-base text-neutral-600">불러오는 중...</div>;
  }
  if (isInvalidId || isError || !facility) {
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
