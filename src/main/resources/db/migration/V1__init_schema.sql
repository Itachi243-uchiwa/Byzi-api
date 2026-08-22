-- Byzi API - schema initial (EPIC-01.1)
-- Miroir du modele JPA. Toutes les FK sont en ON DELETE CASCADE pour que la suppression
-- d'un utilisateur (EPIC-01.6, conformite RGPD) purge automatiquement ses donnees enfants
-- sans dependre d'une orchestration applicative fragile.
--
-- Types volontairement ecrits en SQL standard ("timestamp with time zone" plutot que
-- l'alias Postgres "timestamptz", "varchar" plutot que "text") : la meme migration doit
-- pouvoir s'appliquer telle quelle sur Postgres (dev/prod) et sur H2 (tests d'integration),
-- sinon le schema teste n'est pas le schema deploye.

create table users (
                       id                    uuid primary key,
                       apple_sub             varchar(255) not null,
                       email                 varchar(255),
                       role                  varchar(32)  not null default 'USER',
                       subscription_status   varchar(32)  not null default 'TRIAL',
                       last_login_at         timestamp with time zone,
                       created_at            timestamp with time zone not null,
                       updated_at            timestamp with time zone not null,
                       constraint uk_users_apple_sub unique (apple_sub)
);

create table focus_sessions (
                                id                        uuid primary key,
                                user_id                   uuid not null references users (id) on delete cascade,
                                started_at                timestamp with time zone not null,
                                ended_at                  timestamp with time zone,
                                planned_duration_seconds  integer not null,
                                mode                      varchar(32) not null,
                                completed                 boolean not null default false,
                                created_at                timestamp with time zone not null,
                                updated_at                timestamp with time zone not null
);

create index idx_focus_sessions_user_started on focus_sessions (user_id, started_at);

create table streak_records (
                                id             uuid primary key,
                                user_id        uuid not null references users (id) on delete cascade,
                                day            date not null,
                                goal_reached   boolean not null,
                                focus_minutes  integer not null default 0,
                                created_at     timestamp with time zone not null,
                                updated_at     timestamp with time zone not null,
                                constraint uk_streak_user_day unique (user_id, day)
);

create table app_block_rules (
                                 id                    uuid primary key,
                                 user_id               uuid not null references users (id) on delete cascade,
                                 selection_data        varchar(200000) not null,
                                 daily_limit_minutes   integer,
                                 schedule_start        varchar(5),
                                 schedule_end          varchar(5),
                                 is_active             boolean not null default true,
                                 created_at            timestamp with time zone not null,
                                 updated_at            timestamp with time zone not null
);

create index idx_app_block_rules_user on app_block_rules (user_id);

-- Refresh tokens opaques : seul le hash est stocke (jamais la valeur en clair).
create table refresh_tokens (
                                id          uuid primary key,
                                user_id     uuid not null references users (id) on delete cascade,
                                token_hash  varchar(64) not null,
                                expires_at  timestamp with time zone not null,
                                revoked     boolean not null default false,
                                created_at  timestamp with time zone not null,
                                constraint uk_refresh_tokens_hash unique (token_hash)
);

create index idx_refresh_tokens_user on refresh_tokens (user_id);
