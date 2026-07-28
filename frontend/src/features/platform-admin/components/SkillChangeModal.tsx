import { useEffect, useRef, useState } from 'react';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { SKILL_CHANGE_OPTIONS, SKILL_LABEL } from '../constants';
import type { AdminUser, CounselType } from '../types';

interface SkillChangeModalProps {
  user: AdminUser | null;
  currentSkill: CounselType | null;
  currentSkills: CounselType[];
  isLoadingCurrentSkill: boolean;
  onClose: () => void;
  onConfirm: (user: AdminUser, skill: CounselType) => Promise<void>;
  isSubmitting: boolean;
  submitErrorMessage?: string;
}

// 상담원 스킬 변경 모달 — 행 액션 "스킬 변경"(COUNSELOR 대상 행에서만 노출)에서 연다.
// 라디오 단일 선택(사용자 지시 디자인) — "저장"은 기존 배정 전체를 선택한 스킬 하나로 교체한다.
export function SkillChangeModal({
  user,
  currentSkill,
  currentSkills,
  isLoadingCurrentSkill,
  onClose,
  onConfirm,
  isSubmitting,
  submitErrorMessage,
}: SkillChangeModalProps) {
  const [selectedSkill, setSelectedSkill] = useState<CounselType | null>(null);
  // 현재 값 동기화를 이미 시도한 사용자 id — 조회가 늦게 끝나는 동안 사용자가 박스를 먼저
  // 눌러 골랐다면, 뒤늦게 도착한 currentSkill이 그 선택을 덮어쓰지 않게 막는 가드.
  const syncedUserIdRef = useRef<number | null>(null);

  // 새 행에서 모달이 열리면 이전 선택을 지운다.
  useEffect(() => {
    setSelectedSkill(null);
    syncedUserIdRef.current = null;
  }, [user?.id]);

  // 조회(counselor_skills GET)가 끝나면 현재 스킬로 채운다 — 단, 그 사이 사용자가 이미 박스를
  // 눌러 선택(prev !== null)했다면 그 선택을 우선한다.
  useEffect(() => {
    if (!user || isLoadingCurrentSkill || syncedUserIdRef.current === user.id) {
      return;
    }
    syncedUserIdRef.current = user.id;
    setSelectedSkill((prev) => (prev === null ? currentSkill : prev));
  }, [user, currentSkill, isLoadingCurrentSkill]);

  if (!user) {
    return null;
  }

  function handleSave() {
    if (selectedSkill && user) {
      // 실패해도 여기서 별도 처리는 없다 — 모달을 열어둔 채 submitErrorMessage가 아래에 표시된다.
      onConfirm(user, selectedSkill).catch(() => {});
    }
  }

  return (
    <Modal open={Boolean(user)} onClose={onClose} title="상담원 스킬 변경" closeOnOverlayClick={false}>
      <div className="flex w-105 max-w-full flex-col gap-6">
        <p className="m-0 text-sm text-text-muted">
          상담원의 전문 분야를 지정하여 배정 로직을 최적화합니다.
        </p>

        {/* 다중 스킬 상담원을 라디오(단일 선택)로 저장하면 나머지 배정이 경고 없이 사라진다
            (PR머신 2차 검토 P2) — 저장 전에 현재 보유 중인 전체 목록과 축소 사실을 명시한다. */}
        {!isLoadingCurrentSkill && currentSkills.length > 1 && (
          <p role="alert" className="m-0 rounded-xl bg-warning-soft-bg p-3 text-sm text-warning-soft-fg">
            현재 배정된 스킬이 {currentSkills.length}개입니다 (
            {currentSkills.map((skill) => SKILL_LABEL[skill]).join(', ')}). 저장하면 아래에서
            선택한 스킬 하나로 교체되어 나머지 배정은 삭제됩니다.
          </p>
        )}

        <div role="radiogroup" aria-label="상담원 스킬" className="flex flex-col gap-3">
          {SKILL_CHANGE_OPTIONS.map((skill) => (
            // label 태그가 input을 감싸면 브라우저가 기본적으로 박스 클릭 시 라디오를 토글해주지만,
            // disabled 상태의 input 위를 클릭하면 그 기본 동작도 함께 막혀 로딩 중 박스 클릭이
            // 먹통이 될 수 있다 — onClick을 박스 자체에도 명시해 disabled와 무관하게 선택되게 한다.
            <label
              key={skill}
              onClick={() => setSelectedSkill(skill)}
              className={`flex cursor-pointer items-center justify-between rounded-2xl border p-4 ${
                selectedSkill === skill ? 'border-heading' : 'border-border'
              }`}
            >
              <span className="text-base text-heading">{SKILL_LABEL[skill]}</span>
              <input
                type="radio"
                name="admin-user-skill"
                className="h-4 w-4 accent-heading"
                checked={selectedSkill === skill}
                readOnly
              />
            </label>
          ))}
        </div>

        {submitErrorMessage && (
          <p role="alert" className="m-0 text-sm text-danger">
            {submitErrorMessage}
          </p>
        )}

        <div className="-mx-6 -mb-6 flex justify-center gap-3.5 border-t border-border bg-surface-muted px-6 pt-5 pb-6">
          <Button
            type="button"
            variant="secondary"
            size="lg"
            onClick={onClose}
            disabled={isSubmitting}
            className="w-[180px]"
          >
            취소
          </Button>
          <Button
            type="button"
            variant="primary"
            size="lg"
            disabled={!selectedSkill || isSubmitting || isLoadingCurrentSkill}
            onClick={handleSave}
            className="flex-1"
          >
            {isSubmitting ? '저장 중...' : '변경 내용 저장'}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
