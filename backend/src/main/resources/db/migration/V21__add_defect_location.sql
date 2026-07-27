alter table defects
    add column if not exists location text;
comment on column defects.location is '하자 위치 텍스트(예: 외벽 동측 12층 부근) — 검수자 사후 편집';
