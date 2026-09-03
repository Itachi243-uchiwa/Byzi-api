-- Jour d'écriture d'une tâche / d'un objectif, tel que l'utilisateur l'a vécu.
--
-- `created_at` est une colonne d'AUDIT : elle est renseignée par @CreatedDate au moment où la
-- ligne atteint le serveur. C'est la bonne valeur pour tracer, et la MAUVAISE pour afficher
-- « Écrit le … » : une tâche notée à 23 h 50 hors ligne et synchronisée à 00 h 10 changerait
-- de jour, et une réinstallation daterait toutes les tâches du jour de la resynchronisation.
--
-- D'où une colonne séparée, fournie par le client à la création. Nullable : les lignes
-- antérieures — et les clients plus anciens qui ne l'envoient pas — retombent sur created_at
-- (cf. TodoTaskMapper#writtenAt). On ne la met JAMAIS à jour ensuite : un autre appareil ne
-- réécrit pas l'histoire d'une tâche déjà créée.

alter table todo_tasks add column client_created_at timestamp with time zone;
alter table weekly_objectives add column client_created_at timestamp with time zone;

-- Rattrapage des lignes existantes : la meilleure approximation dont on dispose.
update todo_tasks set client_created_at = created_at where client_created_at is null;
update weekly_objectives set client_created_at = created_at where client_created_at is null;
