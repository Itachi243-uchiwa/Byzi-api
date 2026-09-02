-- To-do list hebdomadaire (backlog app 0ter T9).
--
-- Troisième ressource synchronisée « à la app_block_rules » : upsert idempotent piloté par
-- le client (l'id est un UUID généré sur l'appareil), delta par updated_at, et suppression
-- LOGIQUE via deleted_at pour que la suppression se propage aux autres appareils.
--
-- week_key : le premier jour de la semaine au format ISO « AAAA-MM-JJ », calculé par l'app
-- selon la locale de l'appareil (lundi en FR/BE, dimanche aux US). Le serveur ne l'interprète
-- jamais — il le stocke et le renvoie, exactement comme schedule_start/schedule_end. C'est
-- une chaîne et non une date parce que c'est une CLÉ de regroupement, pas un instant :
-- la convertir en date obligerait le serveur à choisir un fuseau, donc à se tromper.
--
-- due_date : jour d'échéance affiché, même raisonnement (clé de jour, pas un timestamp).
--
-- Types en SQL standard (Postgres dev/prod + H2 en test), comme les migrations précédentes.

create table todo_tasks (
                            id           uuid primary key,
                            user_id      uuid not null references users (id) on delete cascade,
                            title        varchar(200) not null,
                            notes        varchar(2000),
                            week_key     varchar(10) not null,
                            due_date     varchar(10),
                            is_done      boolean not null default false,
                            done_at      timestamp with time zone,
                            created_at   timestamp with time zone not null,
                            updated_at   timestamp with time zone not null,
                            deleted_at   timestamp with time zone
);

-- Le delta de synchronisation filtre sur (user_id, updated_at) : même index que les autres
-- ressources synchronisées.
create index idx_todo_tasks_user_updated on todo_tasks (user_id, updated_at);

-- L'écran « Semaine » liste les tâches d'une semaine donnée, hors supprimées.
create index idx_todo_tasks_user_week on todo_tasks (user_id, week_key);
