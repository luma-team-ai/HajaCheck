import { useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { FacilityCardGrid } from '../components/FacilityCardGrid';
import { FacilityFilterBar } from '../components/FacilityFilterBar';
import { FacilityFormModal } from '../components/FacilityFormModal';
import { useCreateFacility } from '../hooks/useCreateFacility';
import { useFacilities } from '../hooks/useFacilities';
import { useUploadFacilityPhotos } from '../hooks/useUploadFacilityPhotos';
import type { CreateFacilityRequest } from '../types';
import { FACILITY_LIST_FILTERS_INITIAL, filterFacilities } from '../utils/filterFacilities';
import type { FacilityListFilters } from '../utils/filterFacilities';

export function FacilityListPage() {
  const [isModalOpen, setIsModalOpen] = useState(false);
  // 주소 좌표 자동계산(Geocoder) 실패 best-effort 안내(#629, 사용자 결정) — 등록 자체는 막지 않으므로
  // 모달이 곧 닫히는 성공 경로에서도 사용자가 사실을 인지할 수 있도록 이 페이지에 인라인으로 남긴다
  // (이 레포는 별도 Toast 시스템을 두지 않는 컨벤션).
  const [geocodeWarningMessage, setGeocodeWarningMessage] = useState<string | undefined>(undefined);
  // 검색+유형/지역/등급 필터(#810) — 백엔드 변경 없이 클라이언트 사이드로 처리(utils/filterFacilities.ts).
  const [filters, setFilters] = useState<FacilityListFilters>(FACILITY_LIST_FILTERS_INITIAL);
  const navigate = useNavigate();
  const { data: facilities, isLoading, isError, refetch } = useFacilities();
  const {
    createFacility,
    isPending: isCreating,
    error: createError,
    resetError: resetCreateError,
  } = useCreateFacility();
  const {
    uploadPhotos,
    isPending: isUploading,
    error: uploadError,
    resetError: resetUploadError,
  } = useUploadFacilityPhotos();
  // 생성(POST /api/facilities) + 업로드(POST /api/facilities/{id}/media)는 handleSubmit 안에서 순차
  // 실행되는 하나의 논리적 제출이다 — 둘 중 하나만 반영하면 두 가지 회귀가 생긴다: ① 사진 업로드가
  // 진행 중인 동안 createFacility의 isPending만 보고 있으면 이미 false로 풀려 등록 버튼이 재활성화돼
  // 재클릭 시 시설물이 중복 생성된다. ② uploadPhotos가 실패해도 error가 createError(=null, 생성은
  // 이미 성공)만 가리키면 실패 배너가 전혀 뜨지 않는다(code-reviewer P1). 두 뮤테이션의 상태를 합쳐서
  // FacilityFormModal에 전달한다.
  const isPending = isCreating || isUploading;
  const error = createError ?? uploadError;
  const resetError = () => {
    resetCreateError();
    resetUploadError();
  };
  // #1098 — 시설물 생성은 성공했는데 이어지는 사진 업로드만 실패하면, 폼은 실패 상태로 남아
  // 사용자가 같은 폼으로 재제출할 수 있다. 이때 createFacility를 다시 부르면 이미 생성된
  // 시설물과 별개로 중복 시설물이 생긴다 — 생성이 성공한 시점의 facility.id를 기억해두고,
  // 재제출 시에는 재생성 없이 그 id로 업로드만 재시도한다.
  const [pendingFacilityId, setPendingFacilityId] = useState<number | null>(null);
  // #1098 P1(PR머신 재검수) — Modal의 Escape 핸들러는 isSubmitting을 모르고 무조건 onClose를
  // 호출한다(버튼의 disabled={isSubmitting}과 달리 보호되지 않음). 업로드가 in-flight인 동안
  // Escape로 닫으면 handleCloseModal이 즉시 실행되는데, 그 뒤 뒤늦게 도착하는 uploadPhotos의
  // catch가 "이미 포기한" facilityId로 pendingFacilityId를 되살릴 수 있다 — 그러면 다음의
  // 전혀 무관한 새 등록이 재생성 없이 그 옛 시설물에 사진을 붙이는 경쟁 조건이 생긴다. 제출마다
  // 값을 올리는 토큰으로 "이 제출이 아직 유효한가"를 판별해 뒤늦은 catch를 무시한다.
  const submissionTokenRef = useRef(0);

  const isFilterActive =
    filters.search !== '' || filters.type !== '' || filters.region !== '' || filters.grade !== '';
  const filteredFacilities = useMemo(
    () => (facilities ? filterFacilities(facilities, filters) : facilities),
    [facilities, filters],
  );

  const handleOpenModal = () => {
    setGeocodeWarningMessage(undefined);
    setIsModalOpen(true);
  };
  const handleCloseModal = () => {
    setIsModalOpen(false);
    // 모달을 닫을 때(성공/실패/취소/Escape 등 어떤 경로든) 이전 실패의 에러를 초기화 —
    // 그러지 않으면 재오픈 시 지난 세션의 에러 메시지가 즉시 다시 노출된다.
    resetError();
    // 사용자가 업로드 실패 상태를 포기하고 닫으면(취소/Escape), 다음 "시설물 등록" 클릭은
    // 완전히 새 등록으로 취급해야 한다 — pendingFacilityId를 남겨두면 무관한 다음 등록 시도가
    // 엉뚱하게 이전 시설물에 사진을 붙이게 된다(#1098).
    setPendingFacilityId(null);
    // 현재 제출을 무효화 — 이 시점 이후 도착하는 handleSubmit의 catch는 더 이상 pendingFacilityId를
    // 되살리지 못한다(#1098 P1, 위 submissionTokenRef 주석 참고).
    submissionTokenRef.current += 1;
  };

  // 시설물 이름 클릭 → 하자 오버레이(HAJA-434 갭1, Figma node-id=1-3958 흐름)로 직행.
  // 대표 하자가 없는 시설물(하자 미탐지)은 갈 곳이 없으므로 기존 시설물 상세로 폴백한다.
  const handleSelectFacility = (id: number, latestDefectId: number | null) => {
    navigate(
      latestDefectId != null ? `/facilities/${id}/defects/${latestDefectId}` : `/facilities/${id}`,
    );
  };

  const handleSubmit = async (payload: CreateFacilityRequest, photos: File[]) => {
    // 실패 시 여기서 잡지 않고 그대로 전파한다 — FacilityFormModal이 실패를 감지해 폼 값을
    // 초기화하지 않고 유지해야 하므로(등록 실패 시 사용자가 입력한 내용을 잃지 않도록),
    // 이 함수가 던지는 rejection을 FacilityFormModal의 handleSubmit이 catch한다.
    // #1098 — pendingFacilityId가 있으면 이전 시도에서 생성까지는 성공하고 업로드만 실패한
    // 상태다. 재생성하지 않고 그 id로 업로드만 재시도한다. 이 경로에서는 방금 다시 빌드된
    // payload(폼의 최신 값)를 의도적으로 사용하지 않는다 — 즉, 실패 후 재시도 전에 사용자가
    // 이름/유형/주소 등을 고쳤더라도 이미 생성된 시설물에는 반영되지 않는다. 필드를 잠그거나
    // 별도 안내를 추가하는 건 더 큰 UX 작업(옵션 b)이라 이번 스코프에서는 의도적으로
    // 보류한다 — 알려진 트레이드오프이지 누락이 아니다.
    const submissionToken = ++submissionTokenRef.current;
    const facilityId = pendingFacilityId ?? (await createFacility(payload)).id;
    // 사진 업로드는 시설물 생성이 성공한 뒤에만 가능하다(POST /api/facilities/{id}/media, #652) —
    // 사진을 선택하지 않았으면 호출하지 않는다.
    if (photos.length > 0) {
      try {
        await uploadPhotos(facilityId, photos);
      } catch (uploadFailure) {
        // 시설물 생성은 이미 끝났다 — 재생성을 막기 위해 id를 기억해두고 다시 던진다(#1098).
        // 단, 이 catch가 실행되기 전에 사용자가 이미 모달을 닫았다면(Escape 등, #1098 P1)
        // submissionToken이 더 이상 최신이 아니므로 되살리지 않는다 — 이미 포기된 시도다.
        if (submissionTokenRef.current === submissionToken) {
          setPendingFacilityId(facilityId);
        }
        throw uploadFailure;
      }
    }
    // #1098 P1(PR머신 3차 재검수) — handleCloseModal()도 토큰 검증 안에 넣는다. 그러지 않으면
    // 이미 포기된(stale) 제출의 뒤늦은 성공 응답이, 그 사이 사용자가 새로 시작한 완전히 다른
    // 제출의 모달을 강제로 닫고 그 제출의 토큰까지 어긋나게 만드는 2차 경쟁 조건이 생긴다.
    // stale 제출은 성공/실패와 무관하게 다른 제출의 상태를 건드리지 않고 조용히 끝나야 한다.
    if (submissionTokenRef.current === submissionToken) {
      setPendingFacilityId(null);
      handleCloseModal();
    }
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
          <Button variant="primary" onClick={handleOpenModal}>
            + 시설물 등록
          </Button>
        </div>
      </div>

      {geocodeWarningMessage && (
        <p role="alert" className="m-0 text-sm text-warning-soft-fg">
          {geocodeWarningMessage}
        </p>
      )}

      <FacilityFilterBar facilities={facilities ?? []} filters={filters} onChange={setFilters} />

      <FacilityCardGrid
        facilities={filteredFacilities}
        isLoading={isLoading}
        isError={isError}
        onRetry={refetch}
        onSelectFacility={handleSelectFacility}
        emptyMessage={
          isFilterActive
            ? '검색/필터 조건에 맞는 시설물이 없습니다.'
            : '등록된 시설물이 없습니다. 시설물을 등록해 주세요.'
        }
      />

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
