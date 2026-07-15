import type { ChangeEvent, FormEvent } from 'react';
import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { FACILITY_TYPE_OPTIONS } from '../constants';
import type { CreateFacilityRequest } from '../types';
import type { FacilityFormErrors, FacilityFormValues } from '../utils/validateFacilityForm';
import {
  FACILITY_FORM_INITIAL_VALUES,
  hasFacilityFormErrors,
  toCreateFacilityRequest,
  validateFacilityForm,
} from '../utils/validateFacilityForm';

type Props = {
  open: boolean;
  onClose: () => void;
  onSubmit: (payload: CreateFacilityRequest) => Promise<void>;
  isSubmitting: boolean;
  submitErrorMessage?: string;
};

const INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface-muted px-3 py-2 text-sm text-text-default outline-none focus:ring-2 focus:ring-primary';
const LABEL_CLASSES = 'text-sm font-medium text-text-default';
const ERROR_CLASSES = 'text-xs text-danger';

export function FacilityFormModal({
  open,
  onClose,
  onSubmit,
  isSubmitting,
  submitErrorMessage,
}: Props) {
  const [values, setValues] = useState<FacilityFormValues>(FACILITY_FORM_INITIAL_VALUES);
  const [errors, setErrors] = useState<FacilityFormErrors>({});

  const handleChange =
    (field: keyof FacilityFormValues) =>
    (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      setValues((prev) => ({ ...prev, [field]: event.target.value }));
    };

  const handleClose = () => {
    setValues(FACILITY_FORM_INITIAL_VALUES);
    setErrors({});
    onClose();
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const nextErrors = validateFacilityForm(values);
    setErrors(nextErrors);
    if (hasFacilityFormErrors(nextErrors)) {
      return;
    }
    await onSubmit(toCreateFacilityRequest(values));
    setValues(FACILITY_FORM_INITIAL_VALUES);
  };

  return (
    <Modal open={open} onClose={handleClose}>
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

      <form className="flex w-[420px] max-w-full flex-col gap-4" onSubmit={handleSubmit} noValidate>
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

        <div className="flex flex-col gap-1">
          <label htmlFor="facility-address" className={LABEL_CLASSES}>
            주소
          </label>
          <input
            id="facility-address"
            type="text"
            value={values.address}
            onChange={handleChange('address')}
            maxLength={300}
            placeholder="주소를 입력하세요"
            className={INPUT_CLASSES}
            aria-invalid={Boolean(errors.address)}
            aria-describedby={errors.address ? 'facility-address-error' : undefined}
          />
          {errors.address && (
            <p id="facility-address-error" className={ERROR_CLASSES}>
              {errors.address}
            </p>
          )}
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="flex flex-col gap-1">
            <label htmlFor="facility-latitude" className={LABEL_CLASSES}>
              위도
            </label>
            <input
              id="facility-latitude"
              type="text"
              inputMode="decimal"
              value={values.latitude}
              onChange={handleChange('latitude')}
              placeholder="-90 ~ 90"
              className={INPUT_CLASSES}
              aria-invalid={Boolean(errors.latitude)}
              aria-describedby={errors.latitude ? 'facility-latitude-error' : undefined}
            />
            {errors.latitude && (
              <p id="facility-latitude-error" className={ERROR_CLASSES}>
                {errors.latitude}
              </p>
            )}
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="facility-longitude" className={LABEL_CLASSES}>
              경도
            </label>
            <input
              id="facility-longitude"
              type="text"
              inputMode="decimal"
              value={values.longitude}
              onChange={handleChange('longitude')}
              placeholder="-180 ~ 180"
              className={INPUT_CLASSES}
              aria-invalid={Boolean(errors.longitude)}
              aria-describedby={errors.longitude ? 'facility-longitude-error' : undefined}
            />
            {errors.longitude && (
              <p id="facility-longitude-error" className={ERROR_CLASSES}>
                {errors.longitude}
              </p>
            )}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
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

          <div className="flex flex-col gap-1">
            <label htmlFor="facility-inspection-cycle" className={LABEL_CLASSES}>
              점검주기(개월)
            </label>
            <input
              id="facility-inspection-cycle"
              type="text"
              inputMode="numeric"
              value={values.inspectionCycleMonths}
              onChange={handleChange('inspectionCycleMonths')}
              placeholder="예: 6"
              className={INPUT_CLASSES}
              aria-invalid={Boolean(errors.inspectionCycleMonths)}
              aria-describedby={
                errors.inspectionCycleMonths ? 'facility-inspection-cycle-error' : undefined
              }
            />
            {errors.inspectionCycleMonths && (
              <p id="facility-inspection-cycle-error" className={ERROR_CLASSES}>
                {errors.inspectionCycleMonths}
              </p>
            )}
          </div>
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="facility-scale" className={LABEL_CLASSES}>
            규모
          </label>
          <input
            id="facility-scale"
            type="text"
            value={values.scale}
            onChange={handleChange('scale')}
            maxLength={100}
            placeholder="예: 지상 20층, 지하 5층"
            className={INPUT_CLASSES}
            aria-invalid={Boolean(errors.scale)}
            aria-describedby={errors.scale ? 'facility-scale-error' : undefined}
          />
          {errors.scale && (
            <p id="facility-scale-error" className={ERROR_CLASSES}>
              {errors.scale}
            </p>
          )}
        </div>

        {submitErrorMessage && (
          <p role="alert" className="text-sm text-danger">
            {submitErrorMessage}
          </p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={handleClose} disabled={isSubmitting}>
            취소
          </Button>
          <Button type="submit" variant="primary" disabled={isSubmitting}>
            {isSubmitting ? '등록 중...' : '등록하기'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
