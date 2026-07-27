alter table defects
    add column if not exists previous_defect_id bigint references defects (id);
comment on column defects.previous_defect_id is '이전 회차 대응 하자 id(검수자 확정, self-referencing) — 회차 간 비교용';
