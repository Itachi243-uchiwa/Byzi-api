-- Blocage recurrent programme (backlog 10.7) : jours de la semaine.
--
-- app_block_rules portait deja schedule_start et schedule_end, mais aucune notion de jour :
-- une plage horaire s'appliquait donc a tous les jours indistinctement. Le cas d'usage
-- central de la cible (etudiants, entrepreneurs) est pourtant "du lundi au vendredi, 9h-18h"
-- - une plage qui ne doit precisement PAS s'appliquer le week-end.
--
-- Stockage : une chaine de numeros de jour ISO-8601 (1 = lundi ... 7 = dimanche), triee et
-- separee par des virgules, par exemple "1,2,3,4,5". Le choix d'une colonne unique plutot
-- que d'une table de jointure suit la logique deja retenue pour selection_data : une regle
-- de blocage est un objet que le client possede en entier et synchronise d'un bloc. Une
-- table separee imposerait une jointure a chaque lecture et une transaction a chaque
-- ecriture, pour une valeur qui ne depasse jamais treize caracteres et n'est jamais
-- interrogee autrement que comme partie de sa regle.
--
-- NULL signifie "tous les jours", et non "aucun jour" : c'est la valeur que portent les
-- regles existantes, dont le comportement ne doit pas changer sous les pieds des clients
-- deja installes.
--
-- La normalisation - tri, dedoublonnage, bornes 1-7, forme exacte de la chaine - est faite
-- en Java a l'ecriture, et c'est la que se joue la validation. La contrainte ci-dessous est
-- un filet, pour qu'aucun autre chemin d'ecriture (script de reprise, correction manuelle en
-- base) n'y depose du texte libre.
--
-- Elle est ecrite avec regexp_like() et non avec l'operateur ~ de PostgreSQL, pour la meme
-- raison de portabilite que le reste des migrations : ~ n'existe pas en H2, sur lequel
-- tournent les tests d'integration, alors que regexp_like() est present dans H2 comme dans
-- PostgreSQL depuis sa version 15 (la production tourne sur 17).
alter table app_block_rules add column schedule_days varchar(13);

alter table app_block_rules add constraint ck_app_block_rules_schedule_days
    check (schedule_days is null or regexp_like(schedule_days, '^[1-7](,[1-7])*$'));
