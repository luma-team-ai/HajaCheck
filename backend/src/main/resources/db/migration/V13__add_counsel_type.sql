create type counsel_type as enum ('USAGE', 'ANALYSIS_RESULT', 'BILLING_ETC');
comment on type counsel_type is '상담 유형(이용 방법/분석 결과/결제·기타)';

alter table counsel_tickets
    add column counsel_type counsel_type not null;
comment on column counsel_tickets.counsel_type is '상담 유형';

create table counselor_skills
(
    counselor_id bigint       not null references users,
    counsel_type counsel_type not null,
    primary key (counselor_id, counsel_type)
);
comment on table counselor_skills is '상담사가 처리 가능한 상담 유형(다대다)';
comment on column counselor_skills.counselor_id is '상담사 사용자 식별자(users, role=COUNSELOR — DB 제약 아닌 서비스 레벨 검증)';
comment on column counselor_skills.counsel_type is '처리 가능한 상담 유형';

create index idx_counselor_skills_counsel_type
    on counselor_skills (counsel_type);
