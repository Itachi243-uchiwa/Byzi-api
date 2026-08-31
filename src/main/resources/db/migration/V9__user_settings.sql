-- Réglages de l'utilisateur (front : objectif de focus quotidien, EPIC-06 / EPIC-05).
--
-- L'app iOS stocke l'objectif quotidien (`FocusGoal`, minutes de focus réel qui font
-- qu'un jour « compte » pour la série) dans UserDefaults. Le synchroniser permet de
-- retrouver le même objectif sur un nouvel appareil, exactement comme les règles de
-- blocage et les streaks.
--
-- Ressource singulière : une seule ligne par utilisateur (contrainte unique sur user_id),
-- créée avec les valeurs par défaut au premier GET. Pas de tombstone ni de delta : la
-- résolution de conflit est le simple last-write-wins sur updated_at (cf. LastWriteStrategy).
--
-- Types en SQL standard (Postgres dev/prod + H2 tests), enum borné par CHECK plutôt que
-- par un type natif, comme les migrations précédentes.

create table user_settings (
                                id                   uuid primary key,
                                user_id              uuid not null references users (id) on delete cascade,
                                daily_goal_minutes   integer not null default 25,
                                created_at           timestamp with time zone not null,
                                updated_at           timestamp with time zone not null,
                                constraint uk_user_settings_user unique (user_id),
                                constraint ck_user_settings_goal check (daily_goal_minutes between 5 and 240)
);

create index idx_user_settings_user_updated on user_settings (user_id, updated_at);
