-- 갭3(#970) location + HAJA-437 previous_defect_id — 같은 PR로 묶여 한 마이그레이션 파일로 합침.
-- V21(#1032)·V22(defect status 4단계 동기화)가 먼저 머지된 뒤 팀 합의로 V23을 건너뛰고 V24를 쓴다.

alter table defects
    add column if not exists location text;
comment on column defects.location is '하자 위치 텍스트(예: 외벽 동측 12층 부근) — 검수자 사후 편집(#970)';

alter table defects
    add column if not exists previous_defect_id bigint references defects (id);
comment on column defects.previous_defect_id is '이전 회차 대응 하자 id(검수자 확정, self-referencing) — 회차 간 비교용(HAJA-437)';
