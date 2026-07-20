import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../../shared/components/Button';
import { BusinessLicenseUpload } from '../components/BusinessLicenseUpload';
import { CompanyAddressField } from '../components/CompanyAddressField';
import { CompanySignupHeroPanel } from '../components/CompanySignupHeroPanel';
import { EmailDomainField } from '../components/EmailDomainField';
import { PasswordStrengthMeter } from '../components/PasswordStrengthMeter';
import { LOGIN_ROUTE } from '../constants';
import {
  ERROR_CLASSES,
  INPUT_CLASSES,
  LABEL_CLASSES,
  PASSWORD_TOGGLE_CLASSES,
  SUCCESS_CLASSES,
} from '../formClasses';
import { useCompanySignup } from '../hooks/useCompanySignup';
import { useEmailAvailability } from '../hooks/useEmailAvailability';
import {
  doPasswordsMatch,
  getPasswordStrength,
  isValidBusinessNumber,
  isValidEmail,
  isValidPassword,
} from '../utils/authFormValidators';
import { validateBusinessLicenseFile } from '../utils/validateBusinessLicenseFile';
import { isCompanySignupFormValid } from '../utils/validateCompanySignupForm';

const ERROR_MESSAGES: Record<string, string> = {
  AUTH_EMAIL_DUPLICATED: '이미 가입된 이메일입니다.',
  AUTH_BUSINESS_NUMBER_DUPLICATED: '이미 등록된 사업자등록번호입니다.',
  FILE_REQUIRED: '사업자등록증 파일을 첨부해 주세요.',
  FILE_INVALID_TYPE: '지원하지 않는 파일 형식입니다. (JPG, PNG, PDF만 가능)',
  FILE_TOO_LARGE: '파일 용량이 너무 큽니다. (최대 10MB)',
  INVALID_INPUT: '입력값을 다시 확인해 주세요.',
  FILE_UPLOAD_FAILED: '파일 업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.',
};
const DEFAULT_ERROR_MESSAGE = '가입 신청에 실패했습니다. 잠시 후 다시 시도해 주세요.';

const INLINE_BTN_CLASSES =
  'shrink-0 cursor-pointer whitespace-nowrap rounded-lg border border-border bg-surface px-4 text-sm font-semibold text-text-default enabled:hover:bg-surface-muted disabled:cursor-not-allowed disabled:text-text-muted';

export function CompanySignupPage() {
  const [emailLocal, setEmailLocal] = useState('');
  const [emailDomain, setEmailDomain] = useState('');
  const [isCustomDomain, setIsCustomDomain] = useState(true);
  // 직접입력 모드에서 마지막으로 입력한 도메인 값 — 프리셋으로 갔다가 다시 직접입력으로
  // 돌아올 때 emailDomain을 이 값으로 복원해 재입력 마찰을 없앤다(code-reviewer P2, #417).
  const [lastCustomDomain, setLastCustomDomain] = useState('');
  // EmailDomainField의 select onChange 한 번에서 onCustomModeChange→onDomainChange가 연달아
  // 호출되는데, 둘 다 같은 렌더의 클로저를 참조해 isCustomDomain state를 읽으면 stale 값이
  // 잡힌다(프리셋 선택 시 방금 false로 바뀐 모드를 아직 true로 봐서 lastCustomDomain을 프리셋
  // 값으로 덮어쓰는 버그). ref는 즉시(동기) 갱신되므로 handleEmailDomainChange가 항상 최신
  // 모드를 보도록 상태와 함께 유지한다.
  const isCustomDomainRef = useRef(true);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);
  const [isConfirmPasswordVisible, setIsConfirmPasswordVisible] = useState(false);
  const [companyName, setCompanyName] = useState('');
  const [businessRegistrationNumber, setBusinessRegistrationNumber] = useState('');
  const [representativeName, setRepresentativeName] = useState('');
  const [address, setAddress] = useState('');
  const [addressDetail, setAddressDetail] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [agreeTermsOfService, setAgreeTermsOfService] = useState(false);
  const [agreePrivacyPolicy, setAgreePrivacyPolicy] = useState(false);
  const [showValidation, setShowValidation] = useState(false);

  const {
    checkEmailAvailability,
    isPending: isCheckingEmail,
    result: emailCheckResult,
    reset: resetEmailCheck,
  } = useEmailAvailability();
  const { signup, isPending, error } = useCompanySignup();

  // 로컬파트 + '@' + 도메인 조합 — 기존 email 흐름(중복확인·검증·제출)이 이 파생값을 그대로
  // 사용한다(#417, EmailDomainField). 둘 다 비면 '@'만 남아 isValidEmail이 false로 판정한다.
  const email = `${emailLocal.trim()}@${emailDomain.trim()}`;

  const handleCheckEmail = () => {
    if (!isValidEmail(email)) return;
    checkEmailAvailability(email.trim());
  };

  // 이메일을 바꾸면 이전 중복확인 결과(stale)를 즉시 무효화 — A로 확인 후 B로 바꿔도
  // A의 "사용 가능" 결과가 남아 잘못된 메시지·제출 판정으로 이어지는 것 방지(PR머신 P2)
  const handleEmailLocalChange = (value: string) => {
    setEmailLocal(value);
    if (emailCheckResult) resetEmailCheck();
  };

  const handleEmailDomainChange = (value: string) => {
    setEmailDomain(value);
    // 직접입력 모드에서 친 값만 "마지막 커스텀 도메인"으로 보존 — 프리셋 선택으로 인한
    // onDomainChange(프리셋값) 호출은 커스텀 값을 덮어쓰면 안 된다. isCustomDomain state가
    // 아니라 ref를 봐야 같은 이벤트 내 onCustomModeChange(false) 직후에도 최신값을 읽는다.
    if (isCustomDomainRef.current) setLastCustomDomain(value);
    if (emailCheckResult) resetEmailCheck();
  };

  const handleEmailCustomModeChange = (isCustom: boolean) => {
    isCustomDomainRef.current = isCustom;
    setIsCustomDomain(isCustom);
    // 직접입력으로 재전환 시 빈 문자열이 아니라 마지막으로 입력했던 커스텀 도메인으로 복원.
    if (isCustom) setEmailDomain(lastCustomDomain);
    if (emailCheckResult) resetEmailCheck();
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setShowValidation(true);

    const form = {
      email,
      password,
      confirmPassword,
      companyName,
      businessRegistrationNumber,
      representativeName,
      address,
      businessRegistrationFile: file,
      agreeTermsOfService,
      agreePrivacyPolicy,
    };
    if (!isCompanySignupFormValid(form)) return;
    if (emailCheckResult?.available === false) return;

    signup({
      email: email.trim(),
      password,
      companyName: companyName.trim(),
      businessRegistrationNumber,
      representativeName: representativeName.trim(),
      address,
      addressDetail,
      agreeTermsOfService,
      agreePrivacyPolicy,
      businessRegistrationFile: file as File,
    });
  };

  const submitErrorMessage = error ? (ERROR_MESSAGES[error.code] ?? DEFAULT_ERROR_MESSAGE) : null;
  const fileError = showValidation ? validateBusinessLicenseFile(file) : null;

  return (
    <div className="flex min-h-screen bg-white">
      <CompanySignupHeroPanel />

      <section className="flex flex-1 justify-center overflow-y-auto px-6 py-14 sm:px-10 lg:px-16">
        {/* 오른쪽 입력 폼을 테두리 카드로 감싼다(#424) — 왼쪽 히어로(이미지)는 테두리 없음.
            내부 콘텐츠 폭 유지를 위해 max-w를 패딩만큼 넓힌다. */}
        <div className="h-fit w-full max-w-[504px] rounded-2xl border border-border bg-white p-8 shadow-sm">
          <p className="m-0 text-sm font-medium text-text-muted">기업 회원가입</p>
          <h1 className="m-0 mt-1.5 text-2xl font-bold text-heading">회사 계정을 만들어 주세요</h1>
          <p className="m-0 mt-2 text-sm text-text-muted">
            기업 승인 후 검수 워크스페이스가 활성화됩니다.
          </p>

          <form className="mt-8 flex flex-col gap-5" onSubmit={handleSubmit}>
            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-email">
                아이디(이메일)
              </label>
              <EmailDomainField
                localPart={emailLocal}
                domain={emailDomain}
                isCustomDomain={isCustomDomain}
                onLocalPartChange={handleEmailLocalChange}
                onDomainChange={handleEmailDomainChange}
                onCustomModeChange={handleEmailCustomModeChange}
              />
              <button
                type="button"
                className={`${INLINE_BTN_CLASSES} self-start`}
                onClick={handleCheckEmail}
                disabled={isCheckingEmail || !isValidEmail(email)}
              >
                중복확인
              </button>
              {emailCheckResult && (
                <p className={emailCheckResult.available ? SUCCESS_CLASSES : ERROR_CLASSES}>
                  {emailCheckResult.available ? '사용 가능한 이메일입니다.' : '이미 가입된 이메일입니다.'}
                </p>
              )}
              {/* 실시간 인라인 검증(#424) — 입력 중(로컬파트·도메인 중 하나라도 입력)엔 즉시,
                  제출 시엔 빈 값도 안내. 조합 이메일은 항상 '@'를 포함해 length>0이므로
                  로컬파트/도메인 각각의 입력 여부로 판정한다(#417). */}
              {(emailLocal.length > 0 || emailDomain.length > 0 || showValidation) &&
                !isValidEmail(email) && <p className={ERROR_CLASSES}>올바른 이메일 형식을 입력해 주세요.</p>}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-password">
                비밀번호
              </label>
              <div className="relative flex items-center">
                <input
                  id="signup-password"
                  type={isPasswordVisible ? 'text' : 'password'}
                  className={`${INPUT_CLASSES} pr-11`}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="new-password"
                  // 시안 문구는 "8자 이상"만 요구하나 실제 검증(isValidPassword)·에러 문구는
                  // "8자 이상 + 영문+숫자 포함"이라, 시안 그대로 두면 8자만 채우고 통과를 기대하게
                  // 된다(placeholder-검증 불일치, PR머신 P3) — 실제 규칙에 맞춰 정확성 우선(#292)
                  placeholder="8자 이상, 영문+숫자 포함"
                />
                <button
                  type="button"
                  className={PASSWORD_TOGGLE_CLASSES}
                  aria-label={isPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                  onClick={() => setIsPasswordVisible((prev) => !prev)}
                >
                  {isPasswordVisible ? '🙈' : '👁'}
                </button>
              </div>
              <PasswordStrengthMeter strength={getPasswordStrength(password)} />
              {/* 실시간 인라인 검증(#424) — 입력 중(비어있지 않음)엔 즉시, 제출 시엔 빈 값도 안내 */}
              {(password.length > 0 || showValidation) && !isValidPassword(password) && (
                <p className={ERROR_CLASSES}>8자 이상, 영문+숫자를 포함해 주세요.</p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className={LABEL_CLASSES} htmlFor="signup-confirm-password">
                비밀번호 재입력
              </label>
              <div className="relative flex items-center">
                <input
                  id="signup-confirm-password"
                  type={isConfirmPasswordVisible ? 'text' : 'password'}
                  className={`${INPUT_CLASSES} pr-11`}
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  placeholder="비밀번호를 다시 입력해 주세요"
                />
                <button
                  type="button"
                  className={PASSWORD_TOGGLE_CLASSES}
                  aria-label={isConfirmPasswordVisible ? '비밀번호 숨기기' : '비밀번호 표시'}
                  onClick={() => setIsConfirmPasswordVisible((prev) => !prev)}
                >
                  {isConfirmPasswordVisible ? '🙈' : '👁'}
                </button>
              </div>
              {confirmPassword && (
                <p className={doPasswordsMatch(password, confirmPassword) ? SUCCESS_CLASSES : ERROR_CLASSES}>
                  {doPasswordsMatch(password, confirmPassword)
                    ? '✓ 비밀번호가 일치합니다'
                    : '비밀번호가 일치하지 않습니다.'}
                </p>
              )}
            </div>

            <CompanyAddressField
              address={address}
              addressDetail={addressDetail}
              onAddressChange={setAddress}
              onAddressDetailChange={setAddressDetail}
            />

            {/* 사업자등록증 블록 — 시안 기준 파일업로드 + 사업자정보 3필드를 카드 하나로 그룹핑(#292) */}
            <div className="flex flex-col gap-4 rounded-xl border border-border p-4">
              <BusinessLicenseUpload
                file={file}
                onFileSelect={setFile}
                errorMessage={fileError ? ERROR_MESSAGES[fileError] : null}
              />

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-business-number">
                  사업자등록번호
                </label>
                <input
                  id="signup-business-number"
                  type="text"
                  className={INPUT_CLASSES}
                  value={businessRegistrationNumber}
                  onChange={(event) => setBusinessRegistrationNumber(event.target.value)}
                  placeholder="000-00-00000"
                />
                {showValidation && !isValidBusinessNumber(businessRegistrationNumber) && (
                  <p className={ERROR_CLASSES}>사업자등록번호 10자리를 입력해 주세요.</p>
                )}
              </div>

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-company-name">
                  상호명
                </label>
                <input
                  id="signup-company-name"
                  type="text"
                  className={INPUT_CLASSES}
                  value={companyName}
                  onChange={(event) => setCompanyName(event.target.value)}
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <label className={LABEL_CLASSES} htmlFor="signup-representative-name">
                  대표자명
                </label>
                <input
                  id="signup-representative-name"
                  type="text"
                  className={INPUT_CLASSES}
                  value={representativeName}
                  onChange={(event) => setRepresentativeName(event.target.value)}
                />
              </div>
            </div>

            {/* 이용약관 동의·개인정보 수집·이용 동의는 개인정보보호법상 별도 동의 대상이라 체크박스를
                분리한다(PR머신 P2) — 시안은 1개 통합 체크박스이나 법적 요건상 2개 분리를 유지한다(#292) */}
            <div className="flex flex-col gap-1.5">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-text-default">
                <input
                  type="checkbox"
                  checked={agreeTermsOfService}
                  onChange={(event) => setAgreeTermsOfService(event.target.checked)}
                />
                (필수) 이용약관에 동의합니다.
              </label>
              {showValidation && !agreeTermsOfService && (
                <p className={ERROR_CLASSES}>이용약관에 동의해야 가입할 수 있습니다.</p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-text-default">
                <input
                  type="checkbox"
                  checked={agreePrivacyPolicy}
                  onChange={(event) => setAgreePrivacyPolicy(event.target.checked)}
                />
                (필수) 개인정보 수집 및 이용에 동의합니다.
              </label>
              {showValidation && !agreePrivacyPolicy && (
                <p className={ERROR_CLASSES}>개인정보 수집·이용에 동의해야 가입할 수 있습니다.</p>
              )}
            </div>

            {submitErrorMessage && <p className={ERROR_CLASSES}>{submitErrorMessage}</p>}

            <Button type="submit" size="lg" className="w-full" disabled={isPending}>
              {isPending ? '신청 중...' : '가입 신청하기'}
            </Button>

            <p className="m-0 text-center text-sm text-text-muted">
              이미 계정이 있으신가요?{' '}
              <Link to={LOGIN_ROUTE} className="font-medium text-heading underline">
                로그인
              </Link>
            </p>
          </form>
        </div>
      </section>
    </div>
  );
}
