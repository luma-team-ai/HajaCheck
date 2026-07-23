import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { FacilityFormModal } from '../components/FacilityFormModal';
import { FacilityTable } from '../components/FacilityTable';
import { useBackfillFacilityGeocode } from '../hooks/useBackfillFacilityGeocode';
import { useCreateFacility } from '../hooks/useCreateFacility';
import { useFacilities } from '../hooks/useFacilities';
import type { CreateFacilityRequest } from '../types';

export function FacilityListPage() {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [backfillMessage, setBackfillMessage] = useState<string | undefined>(undefined);
  // 주소 좌표 자동계산(Geocoder) 실패 best-effort 안내(#629, 사용자 결정) — 등록 자체는 막지 않으므로
  // 모달이 곧 닫히는 성공 경로에서도 사용자가 사실을 인지할 수 있도록 이 페이지에 인라인으로 남긴다
  // (이 레포는 별도 Toast 시스템을 두지 않는 컨벤션 — backfillMessage와 동일 패턴).
  const [geocodeWarningMessage, setGeocodeWarningMessage] = useState<string | undefined>(undefined);
  const navigate = useNavigate();
  const { data: facilities, isLoading, isError, refetch } = useFacilities();
  const { createFacility, isPending, error, resetError } = useCreateFacility();
  const { run: runBackfill, isRunning: isBackfilling } = useBackfillFacilityGeocode();

  const handleOpenModal = () => {
    setGeocodeWarningMessage(undefined);
    setIsModalOpen(true);
  };
  const handleCloseModal = () => {
    setIsModalOpen(false);
    // 모달을 닫을 때(성공/실패/취소/Escape 등 어떤 경로든) 이전 실패의 에러를 초기화 —
    // 그러지 않으면 재오픈 시 지난 세션의 에러 메시지가 즉시 다시 노출된다.
    resetError();
  };

  // 관리자용 일괄 재-geocoding(#618) — 좌표(NULL) 없는 기존 시설물을 프론트 Geocoder로 소급 계산한다.
  const handleBackfill = async () => {
    if (!facilities || facilities.length === 0) {
      return;
    }
    setBackfillMessage(undefined);
    const result = await runBackfill(facilities);
    await refetch();

    if (result.targetCount === 0 && result.skippedNoAddressCount === 0) {
      setBackfillMessage('좌표를 재계산할 시설물이 없습니다.');
      return;
    }

    const parts = [`성공 ${result.succeeded}건`];
    if (result.failures.length > 0) {
      parts.push(`실패 ${result.failures.length}건`);
    }
    if (result.skippedNoAddressCount > 0) {
      parts.push(`주소 없어 건너뜀 ${result.skippedNoAddressCount}건`);
    }
    setBackfillMessage(`좌표 일괄 재계산 완료 — ${parts.join(', ')}`);
  };

  // 시설물 이름 클릭 → 하자 정보 패널(/facilities/:id, dev-04-02, #489)로 이동
  const handleSelectFacility = (id: number) => {
    navigate(`/facilities/${id}`);
  };

  const handleSubmit = async (payload: CreateFacilityRequest) => {
    await createFacility(payload);
    handleCloseModal();
    // 실패 시 여기서 잡지 않고 그대로 전파한다 — FacilityFormModal이 실패를 감지해 폼 값을
    // 초기화하지 않고 유지해야 하므로(등록 실패 시 사용자가 입력한 내용을 잃지 않도록),
    // 이 함수가 던지는 rejection을 FacilityFormModal의 handleSubmit이 catch한다.
  };

  // 주소 좌표 자동계산(Geocoder) 실패 best-effort 안내(#629) — 등록은 이미 좌표 없이 진행되므로
  // 여기서는 사용자에게 사실을 알리는 인라인 배너만 세팅한다.
  const handleGeocodeFailure = (message: string) => {
    setGeocodeWarningMessage(message);
  };

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 px-6 py-8">
      <div className="flex items-center justify-between">
        <h1 className="m-0 flex items-center gap-2 text-xl font-bold text-heading">
          시설물 관리
          {facilities && <span className="text-base font-normal text-text-muted">{facilities.length}</span>}
        </h1>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            onClick={handleBackfill}
            disabled={isBackfilling || !facilities || facilities.length === 0}
          >
            {isBackfilling ? '좌표 재계산 중...' : '좌표 일괄 재계산'}
          </Button>
          <Button variant="primary" onClick={handleOpenModal}>
            + 시설물 등록
          </Button>
        </div>
      </div>

      {backfillMessage && (
        <p role="status" className="m-0 text-sm text-text-muted">
          {backfillMessage}
        </p>
      )}

      {geocodeWarningMessage && (
        <p role="alert" className="m-0 text-sm text-warning-soft-fg">
          {geocodeWarningMessage}
        </p>
      )}

      <div className="overflow-hidden rounded-2xl border border-border bg-surface">
        <FacilityTable
          facilities={facilities}
          isLoading={isLoading}
          isError={isError}
          onRetry={refetch}
          onSelectFacility={handleSelectFacility}
        />
      </div>

      <FacilityFormModal
        open={isModalOpen}
        onClose={handleCloseModal}
        onSubmit={handleSubmit}
        isSubmitting={isPending}
        submitErrorMessage={error?.message}
        onGeocodeFailure={handleGeocodeFailure}
      />
    </div>
  );
}
