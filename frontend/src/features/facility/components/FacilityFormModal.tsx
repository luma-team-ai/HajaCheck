import type { ChangeEvent, FormEvent } from 'react';
import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import {
  GeocodeFailedError,
  GeocodeNotFoundError,
  geocodeAddress,
} from '../../../shared/lib/kakaoMap/geocodeAddress';
import { FACILITY_TYPE_OPTIONS } from '../constants';
import { ERROR_CLASSES, INPUT_CLASSES, LABEL_CLASSES } from '../formClasses';
import { useFacilityAssignableUsers } from '../hooks/useFacilityAssignableUsers';
import type { CreateFacilityRequest, FacilityInitialGrade } from '../types';
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
  onSubmit: (payload: CreateFacilityRequest) => Promise<void>;
  isSubmitting: boolean;
  submitErrorMessage?: string;
};

export function FacilityFormModal({
  open,
  onClose,
  onSubmit,
  isSubmitting,
  submitErrorMessage,
}: Props) {
  const [values, setValues] = useState<FacilityFormValues>(FACILITY_FORM_INITIAL_VALUES);
  const [errors, setErrors] = useState<FacilityFormErrors>({});
  // 주소 → 좌표 변환(Geocoder) 진행 중 여부와 실패 메시지 — 수동 위도/경도 입력을 대체한다(#618).
  const [isGeocoding, setIsGeocoding] = useState(false);
  const [geocodeErrorMessage, setGeocodeErrorMessage] = useState<string | undefined>(undefined);
  // 담당자 select 옵션 — 실 API 없음(types.ts FacilityAssignableUser 주석 참고), MSW 목 전용(#629).
  const { data: assignableUsers, isLoading: isAssignableUsersLoading } =
    useFacilityAssignableUsers();

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
    setValues(FACILITY_FORM_INITIAL_VALUES);
    setErrors({});
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

    // 주소가 입력된 경우에만 좌표 변환을 시도한다 — 주소가 비어 있으면 좌표 없이(null) 등록된다.
    // 상세주소가 아닌 도로명주소(values.address)만 Geocoder에 전달한다 — 동/호수 등 상세주소가
    // 섞이면 Kakao Geocoder 매칭률이 떨어지기 때문(백엔드로는 toCreateFacilityRequest가 합친
    // payload.address가 그대로 전송된다).
    if (values.address.trim()) {
      setGeocodeErrorMessage(undefined);
      setIsGeocoding(true);
      try {
        const { latitude, longitude } = await geocodeAddress(values.address);
        payload.latitude = latitude;
        payload.longitude = longitude;
      } catch (geocodeError) {
        // 조용히 삼키지 않고 사용자에게 표면화 — 등록 자체를 막고 주소를 더 구체적으로 입력하도록 유도한다.
        const message =
          geocodeError instanceof GeocodeNotFoundError || geocodeError instanceof GeocodeFailedError
            ? geocodeError.message
            : '주소 좌표 변환 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
        setGeocodeErrorMessage(message);
        return;
      } finally {
        setIsGeocoding(false);
      }
    }

    try {
      await onSubmit(payload);
      // 성공 확정 후에만 폼을 초기화 — 실패 시(catch)에는 아무것도 하지 않고 사용자가 입력한
      // 값과 에러 배너(submitErrorMessage)를 그대로 유지한다.
      setValues(FACILITY_FORM_INITIAL_VALUES);
    } catch {
      // onSubmit(FacilityListPage.handleSubmit)이 던진 에러 — 여기서는 폼을 유지만 하고
      // 별도 처리는 하지 않는다. 에러 메시지 표시는 submitErrorMessage prop이 담당한다.
    }
  };

  return (
    <Modal open={open} onClose={handleClose} closeOnOverlayClick={false}>
      <div className="mb-4 flex items-start justify-between gap-4">
        <div>
          <h2 className="m-0 text-lg font-bold text-heading">시설물 등록</h2>
          <p className="m-0 mt-1 text-sm text-text-muted">새 시설물을 등록하세요</p>
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
                <option key={option} value={option}>
                  {option}
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
            <p role="alert" className={ERROR_CLASSES}>
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

        {/* 대표 사진 — UI만 구성(#629), 실 업로드 연동은 #652 대기 */}
        <FacilityPhotoUploadField />

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
            {isGeocoding ? '주소 확인 중...' : isSubmitting ? '등록 중...' : '등록하기'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
