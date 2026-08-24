-- Parrainage (backlog 10.8) : code de parrainage, et jours d'essai offerts au parrain comme
-- au filleul.
--
-- users.referral_code est NULLABLE, et ce n'est pas un oubli : le code n'est cree qu'au
-- premier appel de GET /api/v1/referrals/me, c'est-a-dire quand l'utilisateur ouvre l'ecran
-- de partage. Le generer pour tout le monde a l'inscription reviendrait a remplir la table de
-- codes que l'immense majorite des comptes n'utilisera jamais, et imposerait ici une reprise
-- de donnees generant un code aleatoire unique par ligne existante - en SQL, et sans pouvoir
-- reutiliser l'alphabet defini cote Java.
--
-- L'unicite est portee par un index, pas par une contrainte de colonne : les deux valent en
-- PostgreSQL, mais l'index dit mieux ce dont il s'agit ici - une recherche par code, faite a
-- chaque tentative d'utilisation, et qui doit rester un acces direct.
alter table users add column referral_code varchar(10);

create unique index uk_users_referral_code on users (referral_code);

-- Une ligne par utilisation reussie. Le filleul, le parrain, le code utilise et les jours
-- reellement accordes sont conserves : c'est la matiere de la story 17.5 (suivi des
-- conversions au back-office), et la seule trace exploitable le jour ou il faudra repondre a
-- une contestation.
create table referral_redemptions (
                                      id            uuid primary key,
                                      -- Le parrain : proprietaire du code utilise.
                                      referrer_id   uuid not null references users (id) on delete cascade,
                                      -- Le filleul : celui qui a saisi le code.
                                      referred_id   uuid not null references users (id) on delete cascade,
                                      -- Copie du code au moment de l'utilisation. Redondant avec referrer_id
                                      -- tant que le code ne change jamais - et c'est precisement pourquoi il
                                      -- est copie : le jour ou un code serait regenere, l'historique doit
                                      -- rester lisible tel qu'il s'est produit.
                                      code          varchar(10) not null,
                                      -- Jours effectivement accordes au filleul et au parrain. Distincts, car
                                      -- un parrain deja abonne payant n'en recoit aucun (cf. ReferralService) :
                                      -- la ligne existe alors avec 0 pour tracer la conversion malgre tout.
                                      referred_days integer not null,
                                      referrer_days integer not null,
                                      redeemed_at   timestamp with time zone not null,
                                      created_at    timestamp with time zone not null,
                                      updated_at    timestamp with time zone not null,
    -- L'invariant central du dispositif, et la seule protection anti-abus disponible avant
    -- la detection de fraude prevue en V2 (EPIC-22.1) : un compte ne peut etre parraine
    -- qu'une fois. Porte par la base et non par un test applicatif, qui laisserait passer
    -- deux requetes concurrentes.
                                      constraint uk_referral_redemptions_referred unique (referred_id)
);

-- Compter les filleuls d'un parrain est l'operation faite a chaque ouverture de l'ecran de
-- partage : sans index, c'est un balayage complet de la table.
create index idx_referral_redemptions_referrer on referral_redemptions (referrer_id);
