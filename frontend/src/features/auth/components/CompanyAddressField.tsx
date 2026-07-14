import { useState } from 'react';
import { useDaumPostcodeSearch } from '../hooks/useDaumPostcodeSearch';

interface CompanyAddressFieldProps {
  address: string;
  addressDetail: string;
  onAddressChange: (address: string) => void;
  onAddressDetailChange: (addressDetail: string) => void;
}

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
    <div className="auth-form-field">
      <label className="auth-form-label" htmlFor="company-address">
        회사주소
      </label>
      <div className="auth-address-search-row">
        <input
          id="company-address"
          type="text"
          className="auth-form-input"
          value={address}
          readOnly
          placeholder="주소검색 버튼을 눌러 주소를 입력해 주세요"
        />
        <button type="button" className="auth-address-search-btn" onClick={handleSearchClick}>
          주소검색
        </button>
      </div>
      {isSearchUnavailable && (
        <p className="auth-form-error">주소 검색을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.</p>
      )}
      <input
        type="text"
        className="auth-form-input"
        value={addressDetail}
        onChange={(event) => onAddressDetailChange(event.target.value)}
        placeholder="상세주소를 입력해 주세요"
      />
    </div>
  );
}
