-- HAUT-02 - Purge des refresh tokens expires ou revoques (RefreshTokenCleanupJob).
--
-- Types ecrits en SQL standard, comme dans V1 : la meme migration doit s'appliquer telle
-- quelle sur Postgres (dev/prod) et sur H2 (tests d'integration).

-- revoked_at date precisement une revocation (rotation ou deconnexion globale), distincte de
-- created_at (date d'emission) : sans cette colonne, impossible de retenir "7 jours apres
-- revocation" pour un token qui a pu etre cree bien avant d'etre revoque. Nullable : les
-- lignes existantes, revoquees avant cette migration, n'ont pas cette date ; la purge retombe
-- alors sur created_at pour ne pas les rendre immortelles (cf.
-- RefreshTokenRepository.deleteExpiredOrRevokedBefore).
alter table refresh_tokens add column revoked_at timestamp with time zone;

-- Sans cet index, la purge nocturne fait un balayage complet de la table a chaque execution :
-- c'est justement la table que ce correctif cherche a empecher de grossir indefiniment.
create index idx_refresh_tokens_expires_at on refresh_tokens (expires_at);
