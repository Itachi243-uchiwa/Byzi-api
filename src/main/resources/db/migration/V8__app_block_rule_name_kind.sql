-- Nom et type d'une regle de blocage (front EPIC-03 / EPIC-04).
--
-- L'app iOS presente chaque regle avec un libelle utilisateur ("Reseaux sociaux", "Jeux"...)
-- et un type : FOCUS (bloquee seulement pendant une session de focus) ou LIMIT (creneau
-- planifie et/ou quota de temps quotidien). Ces deux champs etaient jusqu'ici locaux a
-- l'appareil ; les synchroniser permet de retrouver une regle nommee a l'identique sur un
-- nouvel appareil.
--
-- Le serveur ne fait que stocker et transporter ces valeurs, exactement comme selection_data
-- et schedule_days : c'est l'app qui arme le blocage.
--
-- Valeurs par defaut sur les regles existantes : name = '' (pas de libelle), kind = 'FOCUS'
-- (comportement le plus proche d'une regle sans planning). NOT NULL des la creation de la
-- colonne pour que le mapping JPA (@Column nullable = false) soit coherent avec la base.
--
-- kind est stocke en varchar (EnumType.STRING) et non en type enum PostgreSQL, pour rester
-- portable H2 (tests d'integration) comme le reste des migrations. La contrainte CHECK borne
-- les valeurs sans dependre d'un type natif.

alter table app_block_rules add column name varchar(100) not null default '';

alter table app_block_rules add column kind varchar(16) not null default 'FOCUS';

alter table app_block_rules add constraint ck_app_block_rules_kind
    check (kind in ('FOCUS', 'LIMIT'));
