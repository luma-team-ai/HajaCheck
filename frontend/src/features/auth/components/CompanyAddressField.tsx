import { useState } from 'react';
import { useDaumPostcodeSearch } from '../hooks/useDaumPostcodeSearch';

interface CompanyAddressFieldProps {
  address: string;
  addressDetail: string;
  onAddressChange: (address: string) => void;
  onAddressDetailChange: (addressDetail: string) => void;
}

const LABEL_CLASSES = 'text-sm font-medium text-text-default';
const INPUT_CLASSES =
  'w-full rounded-lg border border-border bg-surface-muted px-3.5 py-3 text-sm text-text-default outline-none focus:ring-2 focus:ring-primary';
const ERROR_CLASSES = 'text-xs text-danger';

// 회사주소 — 다음(카카오) 우편번호 서비스로 도로명주소 검색, 상세주소는 직접 입력
export function CompanyAddressField({
  address,
  addressDetail,
  onAddressChange,
  onAddressDetailChange,
}: CompanyAddressFieldProps) {
  const { openPostcodeSearch } = useDaumPostcodeSearch();
  const [isSearchUnavailable, setIsSearchUnavailable] = useState(false);

  const handleSearchClick = () => {
    setIsSearchUnavailable(false);
    openPostcodeSearch(
      (foundAddress) => onAddressChange(foundAddress),
      () => setIsSearchUnavailable(true),
    );
  };

  return (
    <div className="flex flex-col gap-1.5">
      <label className={LABEL_CLASSES} htmlFor="company-address">
        회사 주소
      </label>
      <div className="flex gap-2">
        <input
          id="company-address"
          type="text"
          className={INPUT_CLASSES}
          value={address}
          readOnly
          placeholder="주소검색 버튼을 눌러 주소를 입력해 주세요"
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
        type="text"
        className={INPUT_CLASSES}
        value={addressDetail}
        onChange={(event) => onAddressDetailChange(event.target.value)}
        placeholder="상세주소를 입력해 주세요"
      />
      {/* 시안 문구는 "행정안전부 주소 API로 자동 입력됩니다"이나, 실제 연동은 다음(카카오) 우편번호
          서비스(useDaumPostcodeSearch)라 기관명을 그대로 옮기면 사실과 다르다. 실제 연동에 맞게 수정 — A 보고 시 확인 필요 */}
      <p className="m-0 text-xs text-text-muted">우편번호 검색으로 자동 입력됩니다.</p>
    </div>
  );
}
