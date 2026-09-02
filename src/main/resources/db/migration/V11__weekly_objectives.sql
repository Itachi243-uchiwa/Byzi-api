-- Objectifs hebdomadaires (backlog app 0ter T10).
--
-- Un objectif est rattaché à une semaine (même clé que todo_tasks : le premier jour de la
-- semaine, "AAAA-MM-JJ") et pointe vers des tâches de la to-do list. Quand toutes les tâches
-- liées sont cochées, l'objectif est atteint — et ce jour-là compte pour la série, au même
-- titre qu'un jour de focus tenu.
--
-- linked_task_ids : liste d'UUID en TEXTE séparé par des virgules, pas une table de jointure.
-- Le lien n'est jamais interrogé côté serveur (aucun "donne-moi les objectifs de cette
-- tâche") : il est lu et écrit d'un bloc par l'app, exactement comme schedule_days sur
-- app_block_rules. Une table de jointure ajouterait deux requêtes par upsert pour une
-- relation que personne ne parcourt dans ce sens.
--
-- Pas de clé étrangère vers todo_tasks, volontairement : une tâche supprimée ne doit pas
-- faire échouer l'écriture d'un objectif qui la référençait encore (les deux ressources se
-- synchronisent indépendamment et peuvent arriver dans le désordre). L'app ignore les ids
-- qui ne correspondent à aucune tâche.

create table weekly_objectives (
                            id               uuid primary key,
                            user_id          uuid not null references users (id) on delete cascade,
                            title            varchar(200) not null,
                            week_key         varchar(10) not null,
                            linked_task_ids  varchar(4000),
                            is_achieved      boolean not null default false,
                            achieved_at      timestamp with time zone,
                            created_at       timestamp with time zone not null,
                            updated_at       timestamp with time zone not null,
                            deleted_at       timestamp with time zone
);

create index idx_weekly_objectives_user_updated on weekly_objectives (user_id, updated_at);
create index idx_weekly_objectives_user_week on weekly_objectives (user_id, week_key);
