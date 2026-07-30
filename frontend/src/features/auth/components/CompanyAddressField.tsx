import { useState } from 'react';
import { ERROR_CLASSES, INPUT_CLASSES, LABEL_CLASSES } from '../formClasses';
import { useDaumPostcodeSearch } from '../hooks/useDaumPostcodeSearch';

interface CompanyAddressFieldProps {
  address: string;
  addressDetail: string;
  onAddressChange: (address: string) => void;
  onAddressDetailChange: (addressDetail: string) => void;
  // 필수 입력 검증(#1332) — CompanySignupPage가 제출 시 판단해 내려준다(BusinessLicenseUpload가
  // 이미 쓰는 errorMessage prop 패턴과 동일, 컴포넌트는 표시만 담당·규칙은 모른다).
  errorMessage?: string | null;
}

// 회사주소 — 다음(카카오) 우편번호 서비스로 도로명주소 검색, 상세주소는 직접 입력
export function CompanyAddressField({
  address,
  addressDetail,
  onAddressChange,
  onAddressDetailChange,
  errorMessage,
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

  // 스크립트 로드 실패(우편번호 서비스 자체를 못 불러옴)와 필수 입력 누락(#1332)이 동시에 뜰 수
  // 있는 상태라 하나만 노출한다 — 스크립트 로드 실패가 더 근본적인 원인(주소 입력 자체가
  // 불가능한 상태)이라 우선한다.
  const displayErrorMessage = isSearchUnavailable
    ? '주소 검색을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.'
    : errorMessage;

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
          id="company-address-search-button"
          className="shrink-0 cursor-pointer whitespace-nowrap rounded-lg border border-border bg-surface px-4 text-sm font-semibold text-text-default enabled:hover:bg-surface-muted"
          onClick={handleSearchClick}
        >
          주소검색
        </button>
      </div>
      {/* 주소 input은 readOnly라 포커스해도 커서가 뜨지 않는다 — 제출 실패 시 스크롤/포커스
          대상은 이 버튼(id="company-address-search-button")이 되도록 CompanySignupPage가
          document.getElementById로 직접 찾는다(#1332). */}
      {displayErrorMessage && <p className={ERROR_CLASSES}>{displayErrorMessage}</p>}
      <input
        type="text"
        className={INPUT_CLASSES}
        value={addressDetail}
        onChange={(event) => onAddressDetailChange(event.target.value)}
        placeholder="상세주소를 입력해 주세요"
      />
      {/* 시안 문구는 "행정안전부 주소 API로 자동 입력됩니다"이나, 실제 연동은 다음(카카오) 우편번호
          서비스(useDaumPostcodeSearch)라 기관명을 그대로 옮기면 사실과 다르다. 실제 연동에 맞게
          수정 — 시안 문구 정정은 디자이너 확인 대기(#292) */}
      <p className="m-0 text-xs text-text-muted">우편번호 검색으로 자동 입력됩니다.</p>
    </div>
  );
}
