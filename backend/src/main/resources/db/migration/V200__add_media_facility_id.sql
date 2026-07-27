-- Flyway V200 — media.facility_id 추가 + 폴리모픽 소유(inspection XOR facility) 전환(#632/#652, HAJA-377).
--
-- 번호 도약(V18 → V200) 의도: V19~V22 대역이 다른 in-flight 팀원 PR 들과 몰려 있어 충돌을 피하려고
-- 팀 합의로 V200+ 블록으로 건너뛴다. Flyway 는 비연속 버전을 허용하므로 V18→V200 도약은 유효하며
-- 의도된 것이다(중간 번호를 소모하지 않는다).
--
-- 설계(Option B, #632): 새 테이블을 만들지 않고 기존 media 인프라(파일 검증·저장·썸네일)를 그대로
-- 재사용해 "시설물 대표 사진"을 저장한다. 한 media 로우는 inspection_id(점검 중 사진) 또는
-- facility_id(시설물 대표 사진) 중 정확히 하나만 채워진다.
--
-- 3가지 필수 처리(메인 세션 리스크 감사 확정):
--   1) facility_id nullable FK 추가
--   2) inspection_id 의 NOT NULL 제거(엔티티 @Column(nullable=true)/@ManyToOne(optional=true) 와
--      같은 커밋에서 동시 변경 — 어긋나면 ddl-auto=validate 가 기동을 막는다, #531 재발 방지)
--   3) CHECK ((inspection_id IS NOT NULL) <> (facility_id IS NOT NULL)) — 둘 다 비거나 둘 다
--      채워지는 오염 로우를 DB 레벨에서 차단
--
-- 멱등성(#544 P1 회귀 방지): 캐노니컬 DDL(HajaCheck_script.sql)이 이미 이 스키마를 포함한
-- 기존 DB(baseline-on-migrate) 에서도 실패하지 않도록 add column if not exists / drop not null /
-- pg_constraint 존재 가드 / create index if not exists 로 전부 no-op 안전하게 작성한다.

-- 1) facility_id nullable FK
alter table media
    add column if not exists facility_id bigint;

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conname = 'fk_media_facility'
           and conrelid = 'public.media'::regclass
    ) then
        alter table public.media
            add constraint fk_media_facility
                foreign key (facility_id) references public.facilities (id);
    end if;
end
$$;

-- 2) inspection_id NOT NULL 제거(이미 nullable 이면 no-op 성공)
alter table media
    alter column inspection_id drop not null;

-- 3) 폴리모픽 소유 XOR 제약(정확히 하나만 non-null)
do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conname = 'chk_media_inspection_xor_facility'
           and conrelid = 'public.media'::regclass
    ) then
        alter table public.media
            add constraint chk_media_inspection_xor_facility
                check ((inspection_id is not null) <> (facility_id is not null));
    end if;
end
$$;

create index if not exists idx_media_facility
    on media (facility_id)
    where facility_id is not null;

comment on column media.facility_id is '시설물 대표 사진(#632/#652, HAJA-377)이 속한 시설물 식별자 — nullable, inspection_id 와 정확히 하나만 채워진다(chk_media_inspection_xor_facility)';
