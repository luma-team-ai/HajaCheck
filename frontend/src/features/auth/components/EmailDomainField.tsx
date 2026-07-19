import { INPUT_CLASSES } from '../formClasses';

// 기업 회원가입 이메일 도메인 selectbox(#417) — 로컬파트 input + '@' + 도메인 select(+직접입력 input).
// 프리셋 도메인은 국내 주요 이메일 서비스로 한정(사용자 확정). CUSTOM(직접입력)이 기본값이라
// 초기 상태는 기존 자유입력 동작과 동일하다(회귀 위험 최소). 라벨(htmlFor="signup-email")은
// 페이지가 소유 — 이 컴포넌트는 그 id를 가진 로컬파트 input만 렌더한다(연결 유지).
const CUSTOM_DOMAIN_VALUE = '__custom__';

const PRESET_DOMAINS = [
  'naver.com',
  'gmail.com',
  'daum.net',
  'kakao.com',
  'nate.com',
  'hanmail.net',
] as const;

interface EmailDomainFieldProps {
  localPart: string;
  domain: string;
  isCustomDomain: boolean;
  onLocalPartChange: (value: string) => void;
  onDomainChange: (value: string) => void;
  onCustomModeChange: (isCustom: boolean) => void;
}

export function EmailDomainField({
  localPart,
  domain,
  isCustomDomain,
  onLocalPartChange,
  onDomainChange,
  onCustomModeChange,
}: EmailDomainFieldProps) {
  const handleSelectChange = (event: React.ChangeEvent<HTMLSelectElement>) => {
    const value = event.target.value;
    if (value === CUSTOM_DOMAIN_VALUE) {
      onCustomModeChange(true);
      onDomainChange('');
      return;
    }
    onCustomModeChange(false);
    onDomainChange(value);
  };

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-2">
        <input
          id="signup-email"
          type="text"
          className={INPUT_CLASSES}
          value={localPart}
          onChange={(event) => onLocalPartChange(event.target.value)}
          autoComplete="username"
          placeholder="HajaCheck"
        />
        <span aria-hidden="true" className="shrink-0 text-sm text-text-muted">
          @
        </span>
        <select
          className={INPUT_CLASSES}
          value={isCustomDomain ? CUSTOM_DOMAIN_VALUE : domain}
          onChange={handleSelectChange}
          aria-label="이메일 도메인 선택"
        >
          <option value={CUSTOM_DOMAIN_VALUE}>직접입력</option>
          {PRESET_DOMAINS.map((presetDomain) => (
            <option key={presetDomain} value={presetDomain}>
              {presetDomain}
            </option>
          ))}
        </select>
      </div>
      {isCustomDomain && (
        <input
          type="text"
          className={INPUT_CLASSES}
          value={domain}
          onChange={(event) => onDomainChange(event.target.value)}
          aria-label="이메일 도메인 직접입력"
          placeholder="check.com"
        />
      )}
    </div>
  );
}
