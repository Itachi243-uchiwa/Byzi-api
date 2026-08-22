-- EPIC-09 - Back-office admin.
--
-- Les comptes utilisateurs Byzi n'ont pas de mot de passe : ils s'authentifient par Sign in
-- with Apple depuis l'app iOS. Un administrateur, lui, se connecte depuis un navigateur, ou
-- Sign in with Apple n'a pas de sens. D'ou cette colonne, nullable : elle reste vide pour
-- l'immense majorite des comptes, et seuls les comptes ADMIN en portent une valeur.
--
-- Le hash est un BCrypt : la colonne ne doit JAMAIS contenir un mot de passe en clair.
alter table users add column password_hash varchar(72);

-- L'email devient l'identifiant de connexion du back-office : il doit etre unique, sinon
-- findByEmail devient ambigu et la connexion admin depend de l'ordre des lignes.
-- Contrainte volontairement compatible avec plusieurs valeurs NULL : la majorite des comptes
-- n'ont pas d'email (Sign in with Apple avec l'option "Masquer mon adresse e-mail").
alter table users add constraint uk_users_email unique (email);

-- Story 09.7 - journal d'audit des actions admin.
-- Table strictement append-only : aucune action applicative ne met a jour ni ne supprime une
-- ligne. Un journal d'audit modifiable ne prouve rien.
create table admin_audit_log (
                                 id             uuid primary key,
                                 -- Qui : on stocke l'id ET l'identifiant lisible de l'admin. L'id seul deviendrait
                                 -- inexploitable si le compte admin etait supprime plus tard, or un journal
                                 -- d'audit doit rester lisible plus longtemps que ses acteurs.
                                 admin_id       uuid not null,
                                 admin_label    varchar(255) not null,
                                 -- Quoi
                                 action         varchar(64) not null,
                                 -- Sur qui : pas de FK vers users, volontairement. Une contrainte "on delete
                                 -- cascade" effacerait la trace de la suppression d'un compte au moment meme ou
                                 -- elle devient la plus utile (story 09.6).
                                 target_user_id uuid,
                                 details        varchar(1000),
                                 -- Quand
                                 occurred_at    timestamp with time zone not null
);

create index idx_admin_audit_log_occurred on admin_audit_log (occurred_at desc);
create index idx_admin_audit_log_target on admin_audit_log (target_user_id);
