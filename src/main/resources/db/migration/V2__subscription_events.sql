-- EPIC-07.5 - Historique d'abonnement pilote par les webhooks RevenueCat.
--
-- Pourquoi une table d'evenements et pas seulement la colonne users.subscription_status :
--   1. Idempotence. RevenueCat rejoue un webhook tant qu'il n'a pas recu de 2xx ; sans trace
--      du event_id deja traite, un rejeu reappliquerait la transition (et un rejeu tardif
--      pourrait meme ecraser un etat plus recent).
--   2. La story 09.3 (vue detail utilisateur) demande l'historique d'abonnement.
--   3. Support : sans historique, impossible de repondre a "pourquoi ce compte est-il
--      expire ?" autrement qu'en fouillant le dashboard RevenueCat.

alter table users add column subscription_expires_at timestamp with time zone;

create table subscription_events (
                                     id                uuid primary key,
                                     user_id           uuid not null references users (id) on delete cascade,
                                     -- id de l'evenement chez RevenueCat : la contrainte d'unicite EST le
                                     -- mecanisme d'idempotence, garanti par la base et pas par un check applicatif
                                     -- (deux webhooks concurrents passeraient a travers un simple "select if exists").
                                     event_id          varchar(255) not null,
                                     event_type        varchar(64)  not null,
                                     resulting_status  varchar(32)  not null,
                                     expires_at        timestamp with time zone,
                                     -- Date de l'evenement chez RevenueCat, distincte de la date de reception :
                                     -- les webhooks peuvent arriver dans le desordre.
                                     occurred_at       timestamp with time zone not null,
                                     received_at       timestamp with time zone not null,
                                     constraint uk_subscription_events_event_id unique (event_id)
);

create index idx_subscription_events_user on subscription_events (user_id, occurred_at);
