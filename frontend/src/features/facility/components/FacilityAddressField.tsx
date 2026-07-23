import { useState } from 'react';
import { ERROR_CLASSES, INPUT_CLASSES, LABEL_CLASSES } from '../formClasses';
import { useFacilityPostcodeSearch } from '../hooks/useFacilityPostcodeSearch';

interface FacilityAddressFieldProps {
  address: string;
  addressDetail: string;
  onAddressChange: (address: string) => void;
  onAddressDetailChange: (addressDetail: string) => void;
  errorMessage?: string;
}

// 시설물 주소 — auth/components/CompanyAddressField.tsx와 동일 패턴(다음/카카오 우편번호 서비스로
// 도로명주소 검색, 상세주소는 직접 입력). auth 폴더는 다른 팀원 소유라 직접 import하지 않고
// facility 전용으로 복제한다(#629).
export function FacilityAddressField({
  address,
  addressDetail,
  onAddressChange,
  onAddressDetailChange,
  errorMessage,
}: FacilityAddressFieldProps) {
  const { openPostcodeSearch } = useFacilityPostcodeSearch();
  const [isSearchUnavailable, setIsSearchUnavailable] = useState(false);

  const handleSearchClick = () => {
    setIsSearchUnavailable(false);
    openPostcodeSearch(
      (foundAddress) => onAddressChange(foundAddress),
      () => setIsSearchUnavailable(true),
    );
  };

  return (
    <div className="flex flex-col gap-1">
      <label htmlFor="facility-address" className={LABEL_CLASSES}>
        주소
      </label>
      <div className="flex gap-2">
        <input
          id="facility-address"
          type="text"
          className={INPUT_CLASSES}
          value={address}
          readOnly
          placeholder="주소검색 버튼을 눌러 주소를 입력해 주세요"
          aria-invalid={Boolean(errorMessage)}
          aria-describedby={errorMessage ? 'facility-address-error' : undefined}
        />
        <button
          type="button"
          className="shrink-0 cursor-pointer whitespace-nowrap rounded-lg border border-border bg-surface px-4 text-sm font-semibold text-text-default enabled:hover:bg-surface-muted"
          onClick={handleSearchClick}
        >
          주소검색
        </button>
      </div>
      {isSearchUnavailable && (
        <p className={ERROR_CLASSES}>주소 검색을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.</p>
      )}
      <input
        id="facility-address-detail"
        type="text"
        className={INPUT_CLASSES}
        value={addressDetail}
        onChange={(event) => onAddressDetailChange(event.target.value)}
        placeholder="상세주소를 입력해 주세요"
      />
      {errorMessage && (
        <p id="facility-address-error" className={ERROR_CLASSES}>
          {errorMessage}
        </p>
      )}
      <p className="m-0 text-xs text-text-muted">
        등록 시 주소를 기준으로 위치 좌표가 자동으로 계산됩니다.
      </p>
    </div>
  );
}
