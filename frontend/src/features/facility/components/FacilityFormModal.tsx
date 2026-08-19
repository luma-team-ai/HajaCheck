import type { ChangeEvent, FormEvent } from 'react';
import { useEffect, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import {
  GeocodeFailedError,
  GeocodeNotFoundError,
  geocodeAddress,
} from '../../../shared/lib/kakaoMap/geocodeAddress';
import { FACILITY_TYPE_OPTIONS } from '../constants';
import { ERROR_CLASSES, INPUT_CLASSES, LABEL_CLASSES, WARNING_CLASSES } from '../formClasses';
import { useFacilityAssignableUsers } from '../hooks/useFacilityAssignableUsers';
import type { CreateFacilityRequest, Facility, FacilityInitialGrade } from '../types';
import type { FacilityFormErrors, FacilityFormValues } from '../utils/validateFacilityForm';
import {
  FACILITY_FORM_INITIAL_VALUES,
  hasFacilityFormErrors,
  toCreateFacilityRequest,
  validateFacilityForm,
} from '../utils/validateFacilityForm';
import { FacilityAddressField } from './FacilityAddressField';
import { FacilityInitialGradeSelect } from './FacilityInitialGradeSelect';
import { FacilityPhotoUploadField } from './FacilityPhotoUploadField';

type Props = {
  open: boolean;
  onClose: () => void;
  // #652 — 시설물 생성 후에만 사진 업로드가 가능하므로(POST /api/facilities/{id}/media는 facilityId가
  // 필요), 선택된 File[]을 payload와 별개로 넘겨 상위(FacilityListPage)가 생성 응답의 id로 업로드하게 한다.
  // 수정 모드(mode='edit')에서는 항상 빈 배열을 전달한다(#1681 — 대표 사진 재편집은 이번 범위 밖).
  onSubmit: (payload: CreateFacilityRequest, photos: File[]) => Promise<void>;
  isSubmitting: boolean;
  submitErrorMessage?: string;
  // 주소는 있는데 Geocoder(좌표 변환)가 실패했을 때 호출된다 — 등록/수정 자체는 막지 않고 best-effort로
  // 진행하는 정책(사용자 결정, #629 / #1681)이라, 성공 시 이 모달은 곧 닫히므로 실패 사실을 부모가
  // 인라인 배너로 계속 보여줄 수 있게 전달한다.
  onGeocodeFailure?: (message: string) => void;
  // 등록/수정 모드(#1681) — 기본은 기존 동작(등록) 그대로 유지. 수정 모드는 initialFacility로 프리필하고,
  // 재지오코딩 분기(주소 변경 여부·기존 좌표 보존)가 등록 모드와 달라진다(아래 handleSubmit 참고).
  mode?: 'create' | 'edit';
  // 수정 모드 프리필 + 재지오코딩 기준값(원래 주소·좌표) — mode='edit'일 때 필수로 취급한다.
  initialFacility?: Facility | null;
};

// 수정 모드 프리필 — 도로명주소/상세주소 분리 저장을 하지 않으므로(백엔드 address는 단일 컬럼)
// 기존 저장값 전체를 address 필드에 채우고 addressDetail은 비워둔다. 사용자가 주소검색을 다시 누르지
// 않는 한 이 값이 그대로 재제출되므로 handleSubmit의 "주소 변경 여부" 판정 기준값과 반드시 일치해야 한다.
function buildFormValues(mode: 'create' | 'edit', facility?: Facility | null): FacilityFormValues {
  if (mode !== 'edit' || !facility) {
    return FACILITY_FORM_INITIAL_VALUES;
  }
  return {
    name: facility.name,
    type: facility.type,
    address: facility.address ?? '',
    addressDetail: '',
    builtYear: facility.builtYear != null ? String(facility.builtYear) : '',
    initialGrade: facility.initialGrade ?? '',
    assigneeUserId: facility.assigneeUserId != null ? String(facility.assigneeUserId) : '',
    memo: facility.memo ?? '',
  };
}

export function FacilityFormModal({
  open,
  onClose,
  onSubmit,
  isSubmitting,
  submitErrorMessage,
  onGeocodeFailure,
  mode = 'create',
  initialFacility,
}: Props) {
  const isEditMode = mode === 'edit';
  const [values, setValues] = useState<FacilityFormValues>(() => buildFormValues(mode, initialFacility));
  const [errors, setErrors] = useState<FacilityFormErrors>({});
  // 대표 사진 선택 파일(#652) — FacilityPhotoUploadField가 관리하는 미리보기/objectURL과 별개로,
  // 실제 업로드 시 전송할 File[]만 이 상위 state에 미러링한다.
  const [stagedPhotoFiles, setStagedPhotoFiles] = useState<File[]>([]);
  // 주소 → 좌표 변환(Geocoder) 진행 중 여부와 실패 메시지 — 수동 위도/경도 입력을 대체한다(#618).
  const [isGeocoding, setIsGeocoding] = useState(false);
  const [geocodeErrorMessage, setGeocodeErrorMessage] = useState<string | undefined>(undefined);
  // 담당자 select 옵션 — 실 API 없음(types.ts FacilityAssignableUser 주석 참고), MSW 목 전용(#629).
  const { data: assignableUsers, isLoading: isAssignableUsersLoading } =
    useFacilityAssignableUsers();

  // 모달이 열릴 때마다 최신 initialFacility로 다시 프리필한다(#1681) — 이 컴포넌트는 부모(예:
  // FacilityListPage/FacilityDetailPage)에 항상 마운트된 채 open prop으로만 표시가 토글되므로, 이전
  // 오픈에서 남은 입력값이나 stale facility 데이터가 다음 오픈에 그대로 노출되는 것을 막는다.
  useEffect(() => {
    if (open) {
      setValues(buildFormValues(mode, initialFacility));
      setErrors({});
      setGeocodeErrorMessage(undefined);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const handleChange =
    (field: keyof FacilityFormValues) =>
    (event: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
      setValues((prev) => ({ ...prev, [field]: event.target.value }));
    };

  const handleAddressChange = (address: string) => {
    setValues((prev) => ({ ...prev, address }));
    if (geocodeErrorMessage) {
      // 주소를 다시 고치기 시작하면 지난 좌표 변환 실패 메시지를 지운다.
      setGeocodeErrorMessage(undefined);
    }
  };

  const handleAddressDetailChange = (addressDetail: string) => {
    setValues((prev) => ({ ...prev, addressDetail }));
  };

  const handleInitialGradeChange = (initialGrade: FacilityInitialGrade | '') => {
    setValues((prev) => ({ ...prev, initialGrade }));
  };

  const handleClose = () => {
    setValues(buildFormValues(mode, initialFacility));
    setErrors({});
    setStagedPhotoFiles([]);
    setGeocodeErrorMessage(undefined);
    onClose();
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const nextErrors = validateFacilityForm(values);
    setErrors(nextErrors);
    if (hasFacilityFormErrors(nextErrors)) {
      return;
    }

    const payload = toCreateFacilityRequest(values);

    // 수정 모드 lost-update 방지(#1681, #628/#638/#672와 동일 클래스): toCreateFacilityRequest는
    // "오늘" 기준으로 inspectionCycleMonths/nextInspectionDueAt을 매번 새로 계산한다(등록 시점
    // 산정이 목적, computeNextInspectionDueAt 참고). 수정 화면에서 유형을 건드리지 않았는데도 이
    // 재계산이 그대로 적용되면 ① 이미 산정돼 있던 다음 점검일이 이름/메모 등 무관한 필드 수정만으로
    // 매번 "오늘 기준"으로 조용히 밀리고, ② 12종 조합 이전(#731) 자유 입력 유형으로 저장된 기존
    // 시설물은 findFacilityTypeCycleMonths가 매칭하지 못해 null로 덮어써 점검주기 자체가 사라진다.
    // 유형을 실제로 바꾼 경우에만(사용자가 새 유형/주기 조합을 선택한 의도) 새로 산정한다.
    if (isEditMode && initialFacility && values.type.trim() === (initialFacility.type ?? '').trim()) {
      payload.inspectionCycleMonths = initialFacility.inspectionCycleMonths;
      payload.nextInspectionDueAt = initialFacility.nextInspectionDueAt;
    }

    // geocode 실패 경고는 등록(onSubmit)이 실제로 성공한 뒤에만 부모에 알린다 — 재검수 P1:
    // onSubmit 호출 전에 onGeocodeFailure를 부르면 "...등록되었습니다"(과거완료형) 문구가 아직
    // API 호출도 되기 전에, 심지어 onSubmit이 이후 실패하는 경우에도 뜨는 거짓 배너가 된다.
    let pendingGeocodeWarning: string | undefined;

    // 주소가 입력된 경우에만 좌표 변환을 시도한다 — 주소가 비어 있으면 좌표 없이(null) 등록된다.
    // 상세주소가 아닌 도로명주소(values.address)만 Geocoder에 전달한다 — 동/호수 등 상세주소가
    // 섞이면 Kakao Geocoder 매칭률이 떨어지기 때문(백엔드로는 toCreateFacilityRequest가 합친
    // payload.address가 그대로 전송된다).
    if (values.address.trim()) {
      // 수정 모드 재지오코딩 분기(#1681, 이슈 스펙 (a)~(d)):
      // (a) 주소(도로명)가 원래 값에서 바뀌었으면 재지오코딩 시도
      // (b) 주소가 그대로면 사용자가 지도에서 확정했을 수 있는 기존 좌표를 그대로 보존(Geocoder 미호출)
      // (c) 좌표가 애초에 없고 주소는 있으면(주소는 그대로여도) 재지오코딩 시도
      // (d) 실패 시 기존 좌표 유지(등록 모드는 애초에 "기존 좌표"가 없으므로 null 유지로 동일 정책)
      // 등록 모드는 비교 대상(원래 주소)이 없으므로 항상 시도한다(#618 기존 동작 그대로).
      const originalAddress = (initialFacility?.address ?? '').trim();
      const hasExistingCoords = initialFacility?.latitude != null && initialFacility?.longitude != null;
      const addressChanged = isEditMode ? values.address.trim() !== originalAddress : true;
      const shouldAttemptGeocode = !isEditMode || addressChanged || !hasExistingCoords;

      if (shouldAttemptGeocode) {
        setGeocodeErrorMessage(undefined);
        setIsGeocoding(true);
        try {
          const { latitude, longitude } = await geocodeAddress(values.address);
          payload.latitude = latitude;
          payload.longitude = longitude;
        } catch (geocodeError) {
          // best-effort 정책(사용자 결정, #629, 수정 모드는 #1681로 동일 정책 확장): 좌표 변환
          // 실패로 등록/수정 자체를 막지 않는다. 조용히 삼키지는 않고 콘솔 경고 + (성공이 확정된
          // 뒤) 부모 콜백(onGeocodeFailure)으로 인라인 배너 노출까지 표면화한다(이 레포는 별도
          // Toast 시스템을 두지 않는 컨벤션 — DefectStatusReasonModal.tsx 참고).
          const reason =
            geocodeError instanceof GeocodeNotFoundError || geocodeError instanceof GeocodeFailedError
              ? geocodeError.message
              : '주소 좌표 변환 중 오류가 발생했습니다.';
          console.warn(
            `[FacilityFormModal] 주소 좌표 변환 실패 — 좌표 ${isEditMode ? '기존 값을 유지한 채' : '없이'} ${isEditMode ? '수정' : '등록'}을 진행합니다.`,
            geocodeError,
          );
          if (isEditMode) {
            // (d) 실패 시 기존 좌표 유지 — toCreateFacilityRequest 기본값(null)로 두면 사용자가
            // 지도에서 확정했을 수 있는 기존 좌표를 재계산 실패만으로 지워버리게 된다.
            payload.latitude = initialFacility?.latitude ?? null;
            payload.longitude = initialFacility?.longitude ?? null;
            setGeocodeErrorMessage(`주소 좌표 자동 계산에 실패해 기존 좌표를 유지한 채 수정을 진행합니다. (${reason})`);
            pendingGeocodeWarning = `주소 좌표 자동 계산에 실패해 기존 좌표를 유지한 채 수정되었습니다. (${reason})`;
          } else {
            // 등록이 아직 확정 전이므로(onSubmit 호출 전) 모달 내부 즉시 표시용은 진행형 문구로 —
            // 상위 배너용(pendingGeocodeWarning)은 onSubmit 성공이 확정된 뒤에만 과거형으로 노출한다.
            setGeocodeErrorMessage(`주소 좌표 자동 계산에 실패해 좌표 없이 등록을 진행합니다. (${reason})`);
            pendingGeocodeWarning = `주소 좌표 자동 계산에 실패해 좌표 없이 등록되었습니다. (${reason})`;
          }
        } finally {
          setIsGeocoding(false);
        }
      } else {
        // (b) 주소 미변경 + 기존 좌표 존재 — 재지오코딩 없이 기존 좌표를 그대로 유지한다.
        payload.latitude = initialFacility?.latitude ?? null;
        payload.longitude = initialFacility?.longitude ?? null;
      }
    }

    try {
      await onSubmit(payload, stagedPhotoFiles);
      // 성공 확정 후에만 폼을 초기화 — 실패 시(catch)에는 아무것도 하지 않고 사용자가 입력한
      // 값과 에러 배너(submitErrorMessage)를 그대로 유지한다.
      setValues(buildFormValues(mode, initialFacility));
      setStagedPhotoFiles([]);
      // 성공 시 모달은 부모(open=false)로 인해 화면에서만 사라질 뿐 이 컴포넌트 자체는 언마운트되지
      // 않는다 — geocodeErrorMessage를 여기서 지우지 않으면 다음에 모달을 다시 열 때(핸들클로즈를
      // 거치지 않은 경로로) 지난 실패 메시지가 잔류해 보일 수 있어 함께 초기화한다.
      setGeocodeErrorMessage(undefined);
      // 등록이 실제로 성공한 뒤에만 부모에 경고를 전달 — onSubmit이 실패했다면 여기 도달하지
      // 않으므로 onGeocodeFailure는 절대 호출되지 않는다(재검수 P1 수정).
      if (pendingGeocodeWarning) {
        onGeocodeFailure?.(pendingGeocodeWarning);
      }
    } catch {
      // onSubmit(FacilityListPage.handleSubmit)이 던진 에러 — 여기서는 폼을 유지만 하고
      // 별도 처리는 하지 않는다. 에러 메시지 표시는 submitErrorMessage prop이 담당한다.
      // pendingGeocodeWarning은 폐기 — 등록 자체가 실패했으니 "등록되었습니다" 경고를 띄우면 안 된다.
    }
  };

  return (
    <Modal open={open} onClose={handleClose} closeOnOverlayClick={false}>
      <div className="mb-4 flex items-start justify-between gap-4">
        <div>
          <h2 className="m-0 text-lg font-bold text-heading">
            {isEditMode ? '시설물 수정' : '시설물 등록'}
          </h2>
          <p className="m-0 mt-1 text-sm text-text-muted">
            {isEditMode ? '시설물 정보를 수정하세요' : '새 시설물을 등록하세요'}
          </p>
        </div>
        <button
          type="button"
          aria-label="닫기"
          onClick={handleClose}
          className="rounded-full p-1 text-text-muted hover:bg-surface-muted"
        >
          ✕
        </button>
      </div>

      <form className="flex w-105 max-w-full flex-col gap-5" onSubmit={handleSubmit} noValidate>
        {/* 기본 정보 */}
        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="facility-name" className={LABEL_CLASSES}>
              시설물명 <span className="text-danger">*</span>
            </label>
            <input
              id="facility-name"
              type="text"
              value={values.name}
              onChange={handleChange('name')}
              maxLength={200}
              placeholder="예: 강남 오피스타워 A동"
              className={INPUT_CLASSES}
              aria-invalid={Boolean(errors.name)}
              aria-describedby={errors.name ? 'facility-name-error' : undefined}
            />
            {errors.name && (
              <p id="facility-name-error" className={ERROR_CLASSES}>
                {errors.name}
              </p>
            )}
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="facility-type" className={LABEL_CLASSES}>
              시설물 유형 <span className="text-danger">*</span>
            </label>
            <select
              id="facility-type"
              value={values.type}
              onChange={handleChange('type')}
              className={INPUT_CLASSES}
              aria-invalid={Boolean(errors.type)}
              aria-describedby={errors.type ? 'facility-type-error' : undefined}
            >
              <option value="">유형을 선택하세요</option>
              {FACILITY_TYPE_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.value}
                </option>
              ))}
            </select>
            {errors.type && (
              <p id="facility-type-error" className={ERROR_CLASSES}>
                {errors.type}
              </p>
            )}
          </div>

          <FacilityAddressField
            address={values.address}
            addressDetail={values.addressDetail}
            onAddressChange={handleAddressChange}
            onAddressDetailChange={handleAddressDetailChange}
            errorMessage={errors.address}
          />
          {geocodeErrorMessage && (
            // best-effort 경고(#629 재조정) — 필수검증 에러(ERROR_CLASSES, danger)와 달리 등록을
            // 막지 않으므로 warning 톤으로 표시해 사용자가 "실패"로 오인하지 않게 한다(재검수 P2-a).
            <p role="alert" className={WARNING_CLASSES}>
              {geocodeErrorMessage}
            </p>
          )}

          <div className="flex flex-col gap-1">
            <label htmlFor="facility-built-year" className={LABEL_CLASSES}>
              준공년도
            </label>
            <input
              id="facility-built-year"
              type="text"
              inputMode="numeric"
              value={values.builtYear}
              onChange={handleChange('builtYear')}
              placeholder="YYYY"
              className={INPUT_CLASSES}
              aria-invalid={Boolean(errors.builtYear)}
              aria-describedby={errors.builtYear ? 'facility-built-year-error' : undefined}
            />
            {errors.builtYear && (
              <p id="facility-built-year-error" className={ERROR_CLASSES}>
                {errors.builtYear}
              </p>
            )}
          </div>
        </div>

        {/* 대표 사진 — UI(#629) + 실 업로드 연동(#652). 수정 모드는 이번 범위 밖(#1681 — 대표 사진
            재편집은 별도 후속으로, 지금은 등록 시 1회 업로드한 사진을 그대로 유지한다). */}
        {!isEditMode && <FacilityPhotoUploadField onFilesChange={setStagedPhotoFiles} />}

        {/* 초기 등급 설정(선택) */}
        <FacilityInitialGradeSelect value={values.initialGrade} onChange={handleInitialGradeChange} />

        {/* 담당자(선택) / 메모(선택) */}
        <div className="flex flex-col gap-1">
          <label htmlFor="facility-assignee" className={LABEL_CLASSES}>
            담당자
          </label>
          <select
            id="facility-assignee"
            value={values.assigneeUserId}
            onChange={handleChange('assigneeUserId')}
            className={INPUT_CLASSES}
            disabled={isAssignableUsersLoading}
            aria-invalid={Boolean(errors.assigneeUserId)}
            aria-describedby={errors.assigneeUserId ? 'facility-assignee-error' : undefined}
          >
            <option value="">담당자를 선택하세요 (선택)</option>
            {assignableUsers?.map((user) => (
              <option key={user.id} value={user.id}>
                {user.name}
              </option>
            ))}
          </select>
          {errors.assigneeUserId && (
            <p id="facility-assignee-error" className={ERROR_CLASSES}>
              {errors.assigneeUserId}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="facility-memo" className={LABEL_CLASSES}>
            메모
          </label>
          <textarea
            id="facility-memo"
            value={values.memo}
            onChange={handleChange('memo')}
            maxLength={2000}
            rows={3}
            placeholder="메모를 입력하세요 (선택)"
            className={INPUT_CLASSES}
            aria-invalid={Boolean(errors.memo)}
            aria-describedby={errors.memo ? 'facility-memo-error' : undefined}
          />
          {errors.memo && (
            <p id="facility-memo-error" className={ERROR_CLASSES}>
              {errors.memo}
            </p>
          )}
        </div>

        {submitErrorMessage && (
          <p role="alert" className="text-sm text-danger">
            {submitErrorMessage}
          </p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button
            type="button"
            variant="secondary"
            onClick={handleClose}
            disabled={isSubmitting || isGeocoding}
          >
            취소
          </Button>
          <Button type="submit" variant="primary" disabled={isSubmitting || isGeocoding}>
            {isGeocoding
              ? '주소 확인 중...'
              : isSubmitting
                ? isEditMode
                  ? '수정 중...'
                  : '등록 중...'
                : isEditMode
                  ? '수정하기'
                  : '등록하기'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
