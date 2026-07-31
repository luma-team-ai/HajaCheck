import { useNavigate, useParams } from 'react-router-dom';
import '../../../shared/styles/layout.css';
// defect-card / defect-activity-panel 클래스는 DefectDetailPage.css에 정의돼 있다 — 이 페이지가
// 별도로 로드하지 않으므로 ActivityHistoryPanel과 함께 임포트해 재사용한다(DefectDetailModal.tsx와
// 동일 패턴 — 신규 스타일 중복 정의 금지, #1351).
import '../../defect/pages/DefectDetailPage.css';
import { ActivityHistoryPanel } from '../../defect/components/ActivityHistoryPanel';
import { FacilityDefectAiExplainPanel } from '../components/FacilityDefectAiExplainPanel';
import { FacilityDefectImagePanel } from '../components/FacilityDefectImagePanel';
import { FacilityDefectInfoPanel } from '../components/FacilityDefectInfoPanel';
import { useFacility } from '../hooks/useFacility';
import { useFacilityDefectDetail } from '../hooks/useFacilityDefectDetail';

const DEFAULT_ID = 'detail';

// 시설물 상세 하위 드릴다운 — 하자 정보 패널(dev-04-02, #489). /facilities/:id(시설물 개요,
// dev-05-02·#504)에서 특정 하자를 드릴다운하는 화면이라 :id(시설물)와 :defectId(하자)를 함께 받는다.
export function FacilityDefectDetailPage() {
  const { id: facilityId = DEFAULT_ID, defectId = DEFAULT_ID } = useParams<{
    id: string;
    defectId: string;
  }>();
  const navigate = useNavigate();
  const { data: defect, isLoading, isError, refetch } = useFacilityDefectDetail(defectId);
  // #1350 — AI 설명(POST /ai/defect-explain)이 요구하는 facility_type 소스. 이미 존재하는
  // 시설물 상세 조회(useFacility)를 재사용한다 — 새 엔드포인트·DB 조회 불필요. isLoading은
  // FacilityDefectAiExplainPanel에 그대로 넘겨 하자 조회가 먼저 끝나도 시설물 조회 완료 전까지
  // AI 설명 조회를 대기시킨다(code-reviewer P2, PR #1364).
  const { data: facility, isLoading: isFacilityLoading } = useFacility(Number(facilityId));

  // defectId는 라우트 파라미터라 항상 string이고, DEFAULT_ID('detail') 폴백이 그대로 들어오면
  // Number() 변환이 NaN이 된다 — ActivityHistoryPanel(defectId: number)에 NaN을 넘겨 불필요한
  // API 호출(GET /api/defects/NaN/revisions)을 만들지 않도록 가드한다(#1351).
  const numericDefectId = Number(defectId);
  const hasValidDefectId = Number.isFinite(numericDefectId);

  const handleCompareClick = () => {
    navigate(`/facilities/${facilityId}/defects/${defectId}/compare`);
  };

  // "다음 단계로 전이"는 상태 mutation 없이 해당 하자가 속한 점검의 하자 목록으로 이동한다.
  const handleTransitionClick = () => {
    if (defect) {
      navigate(`/inspections/${defect.inspectionId}/defects`);
    }
  };

  if (isLoading) {
    return <div className="dashboard-content text-sm text-text-muted">불러오는 중...</div>;
  }

  if (isError || !defect) {
    return (
      <div className="dashboard-content">
        <p className="m-0 text-sm text-text-muted">하자 정보를 불러오지 못했습니다.</p>
        <button
          type="button"
          onClick={() => refetch()}
          className="self-start text-sm font-semibold text-accent"
        >
          다시 시도
        </button>
      </div>
    );
  }

  return (
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">하자 상세</h1>
      </div>

      <div className="grid gap-8 lg:grid-cols-[1.4fr_1fr]">
        <FacilityDefectImagePanel defect={defect} onCompareClick={handleCompareClick} />
        <FacilityDefectInfoPanel defect={defect} onTransitionClick={handleTransitionClick} />
      </div>

      <FacilityDefectAiExplainPanel
        defectId={defect.id}
        defectType={defect.defectType}
        grade={defect.grade ?? ''}
        location={defect.location ?? ''}
        facilityType={facility?.type ?? ''}
        isFacilityLoading={isFacilityLoading}
      />

      {hasValidDefectId && <ActivityHistoryPanel defectId={numericDefectId} />}
    </div>
  );
}
