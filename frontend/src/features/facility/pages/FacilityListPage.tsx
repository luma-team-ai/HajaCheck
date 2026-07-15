import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { FacilityFormModal } from '../components/FacilityFormModal';
import { FacilityTable } from '../components/FacilityTable';
import { useCreateFacility } from '../hooks/useCreateFacility';
import { useFacilities } from '../hooks/useFacilities';
import type { CreateFacilityRequest } from '../types';

export function FacilityListPage() {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const { data: facilities, isLoading, isError, refetch } = useFacilities();
  const { createFacility, isPending, error, resetError } = useCreateFacility();

  const handleOpenModal = () => setIsModalOpen(true);
  const handleCloseModal = () => {
    setIsModalOpen(false);
    // 모달을 닫을 때(성공/실패/취소/Escape 등 어떤 경로든) 이전 실패의 에러를 초기화 —
    // 그러지 않으면 재오픈 시 지난 세션의 에러 메시지가 즉시 다시 노출된다.
    resetError();
  };

  const handleSubmit = async (payload: CreateFacilityRequest) => {
    await createFacility(payload);
    handleCloseModal();
    // 실패 시 여기서 잡지 않고 그대로 전파한다 — FacilityFormModal이 실패를 감지해 폼 값을
    // 초기화하지 않고 유지해야 하므로(등록 실패 시 사용자가 입력한 내용을 잃지 않도록),
    // 이 함수가 던지는 rejection을 FacilityFormModal의 handleSubmit이 catch한다.
  };

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 px-6 py-8">
      <div className="flex items-center justify-between">
        <h1 className="m-0 flex items-center gap-2 text-xl font-bold text-heading">
          시설물 관리
          {facilities && <span className="text-base font-normal text-text-muted">{facilities.length}</span>}
        </h1>
        <Button variant="primary" onClick={handleOpenModal}>
          + 시설물 등록
        </Button>
      </div>

      <div className="overflow-hidden rounded-2xl border border-border bg-surface">
        <FacilityTable
          facilities={facilities}
          isLoading={isLoading}
          isError={isError}
          onRetry={refetch}
        />
      </div>

      <FacilityFormModal
        open={isModalOpen}
        onClose={handleCloseModal}
        onSubmit={handleSubmit}
        isSubmitting={isPending}
        submitErrorMessage={error?.message}
      />
    </div>
  );
}
