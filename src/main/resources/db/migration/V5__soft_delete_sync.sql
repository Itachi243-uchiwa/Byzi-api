-- Synchronisation multi-appareils : tombstones et delta (audit backend - MANQUE-01/02).
--
-- Probleme resolu : jusqu'ici, DELETE /focus-sessions/{id} effacait physiquement la ligne
-- sans laisser de trace. Sur un second appareil la ressource existait toujours dans
-- SwiftData, et son PUT suivant la RECREAIT. Une suppression ne se propageait donc jamais.
--
-- La colonne deleted_at transforme la suppression en fait observable : la ligne reste en
-- base, sort des listes, et apparait dans le delta pour que chaque appareil sache qu'il
-- doit supprimer sa copie locale.
--
-- Ce mecanisme ne concerne QUE la synchronisation. La suppression de compte (RGPD,
-- AccountDeletionService) reste une suppression PHYSIQUE en cascade : un utilisateur qui
-- exerce son droit a l'effacement ne doit rien laisser derriere lui, tombstone compris.
--
-- Types en SQL standard, pour la meme raison qu'en V1 : la migration doit s'appliquer telle
-- quelle sur Postgres (dev/prod) et sur H2 (tests d'integration).

alter table focus_sessions  add column deleted_at timestamp with time zone;
alter table streak_records  add column deleted_at timestamp with time zone;
alter table app_block_rules add column deleted_at timestamp with time zone;

-- Index du delta : la requete de synchronisation filtre sur (user_id, updated_at >= ?).
-- Sans eux, chaque demarrage de l'app declencherait un balayage complet de la table.
--
-- updated_at et non deleted_at : le delta doit renvoyer AUSSI BIEN les creations et
-- modifications que les suppressions. Un tombstone met a jour updated_at au moment ou il
-- est pose, il est donc naturellement capture par ce meme index.
create index idx_focus_sessions_user_updated  on focus_sessions  (user_id, updated_at);
create index idx_streak_records_user_updated  on streak_records  (user_id, updated_at);
create index idx_app_block_rules_user_updated on app_block_rules (user_id, updated_at);
