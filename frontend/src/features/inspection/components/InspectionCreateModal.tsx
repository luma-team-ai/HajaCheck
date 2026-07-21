import type { ChangeEvent, FormEvent } from 'react';
import { useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { useCreateInspection } from '../hooks/useCreateInspection';
import { useFacilityOptions } from '../hooks/useFacilityOptions';
import type { InspectionCreateResponse } from '../types';
import type {
  InspectionCreateFormErrors,
  InspectionCreateFormValues,
} from '../utils/validateInspectionCreateForm';
import {
  INSPECTION_CREATE_FORM_INITIAL_VALUES,
  hasInspectionCreateFormErrors,
  toInspectionCreateRequest,
  validateInspectionCreateForm,
} from '../utils/validateInspectionCreateForm';

const INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface-muted px-3 py-2 text-sm text-text-default outline-none focus:ring-2 focus:ring-primary';
const LABEL_CLASSES = 'text-sm font-medium text-text-default';
const ERROR_CLASSES = 'text-xs text-danger';

type Props = {
  open: boolean;
  onClose: () => void;
  /** 모달을 연 화면이 보고 있던 시설물 — 열릴 때 셀렉트 기본값으로 채운다 */
  defaultFacilityId?: number;
  onCreated: (response: InspectionCreateResponse) => void;
};

function getInitialValues(defaultFacilityId?: number): InspectionCreateFormValues {
  return {
    ...INSPECTION_CREATE_FORM_INITIAL_VALUES,
    facilityId: defaultFacilityId ? String(defaultFacilityId) : '',
  };
}

// 점검(회차) 생성 — API 명세서 v0.3 AP-004, POST /api/inspections(실 연동).
// 담당자 지정은 배정 가능한 점검자/관리자 목록을 조회하는 API가 아직 없어(백엔드는
// AuthService.validateAssignableInspector로 검증만 하고 목록 엔드포인트가 없음) userId 직접
// 입력으로 대체한다 — 목록 조회 API가 추가되면 select로 교체 예정.
export function InspectionCreateModal({ open, onClose, defaultFacilityId, onCreated }: Props) {
  const { data: facilities, isLoading: isFacilitiesLoading } = useFacilityOptions();
  const { createInspection, isPending, error, resetError } = useCreateInspection();

  const [values, setValues] = useState<InspectionCreateFormValues>(() =>
    getInitialValues(defaultFacilityId),
  );
  const [errors, setErrors] = useState<InspectionCreateFormErrors>({});

  const handleChange =
    (field: keyof InspectionCreateFormValues) =>
    (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      setValues((prev) => ({ ...prev, [field]: event.target.value }));
      resetError();
    };

  const handleClose = () => {
    setValues(getInitialValues(defaultFacilityId));
    setErrors({});
    resetError();
    onClose();
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const nextErrors = validateInspectionCreateForm(values);
    setErrors(nextErrors);
    if (hasInspectionCreateFormErrors(nextErrors)) {
      return;
    }

    try {
      const response = await createInspection(toInspectionCreateRequest(values));
      setValues(getInitialValues(defaultFacilityId));
      onCreated(response);
    } catch {
      // 실패 사유는 error로 아래에 표시 — 폼 값은 유지해 재시도 가능하게 둔다.
    }
  };

  return (
    <Modal open={open} onClose={handleClose} title="점검(회차) 생성">
      <form className="flex w-96 max-w-full flex-col gap-4" onSubmit={handleSubmit} noValidate>
        <div className="flex flex-col gap-1">
          <label htmlFor="inspection-facility" className={LABEL_CLASSES}>
            시설물 <span className="text-danger">*</span>
          </label>
          <select
            id="inspection-facility"
            value={values.facilityId}
            onChange={handleChange('facilityId')}
            className={INPUT_CLASSES}
            disabled={isFacilitiesLoading}
            aria-invalid={Boolean(errors.facilityId)}
            aria-describedby={errors.facilityId ? 'inspection-facility-error' : undefined}
          >
            <option value="">{isFacilitiesLoading ? '불러오는 중...' : '시설물을 선택하세요'}</option>
            {facilities?.map((facility) => (
              <option key={facility.id} value={facility.id}>
                {facility.name}
              </option>
            ))}
          </select>
          {errors.facilityId && (
            <p id="inspection-facility-error" className={ERROR_CLASSES}>
              {errors.facilityId}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="inspection-date" className={LABEL_CLASSES}>
            점검일 <span className="text-danger">*</span>
          </label>
          <input
            id="inspection-date"
            type="date"
            value={values.inspectionDate}
            onChange={handleChange('inspectionDate')}
            className={INPUT_CLASSES}
            aria-invalid={Boolean(errors.inspectionDate)}
            aria-describedby={errors.inspectionDate ? 'inspection-date-error' : undefined}
          />
          {errors.inspectionDate && (
            <p id="inspection-date-error" className={ERROR_CLASSES}>
              {errors.inspectionDate}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="inspection-assignee" className={LABEL_CLASSES}>
            담당자 ID <span className="text-danger">*</span>
          </label>
          <input
            id="inspection-assignee"
            type="text"
            inputMode="numeric"
            value={values.assignedInspectorId}
            onChange={handleChange('assignedInspectorId')}
            placeholder="점검자/관리자 사용자 ID"
            className={INPUT_CLASSES}
            aria-invalid={Boolean(errors.assignedInspectorId)}
            aria-describedby={errors.assignedInspectorId ? 'inspection-assignee-error' : undefined}
          />
          {errors.assignedInspectorId && (
            <p id="inspection-assignee-error" className={ERROR_CLASSES}>
              {errors.assignedInspectorId}
            </p>
          )}
        </div>

        {error && (
          <p role="alert" className="text-sm text-danger">
            {error.message ?? '점검 회차 생성에 실패했습니다.'}
          </p>
        )}

        <div className="mt-2 flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={handleClose} disabled={isPending}>
            취소
          </Button>
          <Button type="submit" variant="primary" disabled={isPending}>
            {isPending ? '생성 중...' : '점검 회차 생성'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
