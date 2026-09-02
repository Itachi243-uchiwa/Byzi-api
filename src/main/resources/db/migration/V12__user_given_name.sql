-- Prénom de l'utilisateur (backlog app 0ter T8).
--
-- Apple ne fournit `fullName` qu'à la TOUTE PREMIÈRE autorisation d'un identifiant : après
-- une réinstallation, l'app ne le récupère plus. Elle propose donc de le saisir, mais il
-- restait local — changer de téléphone le perdait. Le stocker ici, comme le reste du profil,
-- le rend portable.
--
-- Nullable : personne n'est obligé de le donner, et la copie retombe sur une formulation
-- sans prénom.

alter table users add column given_name varchar(100);
