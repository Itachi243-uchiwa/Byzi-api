package com.byzi.api.repository;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByAppleSub(String appleSub);

    /** Connexion au back-office : l'email sert d'identifiant aux comptes ADMIN. */
    Optional<User> findByEmail(String email);

    /**
     * Recherche du back-office (story 09.2). Filtre sur l'email uniquement : l'appleSub est un
     * identifiant opaque fourni par Apple, qu'un agent de support n'a aucun moyen de connaitre.
     * <p>
     * Deliberement SANS clause "or :term is null" pour couvrir aussi le cas "pas de filtre" :
     * PostgreSQL ne sait pas inferer le type d'un parametre lie a null et le traite comme du
     * bytea, ce qui fait echouer lower() a l'execution ("function lower(bytea) does not
     * exist"). Le cas sans terme passe donc par findAll(), cf. AdminUserService.search.
     */
    Page<User> findByEmailContainingIgnoreCase(String term, Pageable pageable);

    long countBySubscriptionStatus(SubscriptionStatus status);

    long countByRole(Role role);

    /**
     * Recherche par code de parrainage (backlog 10.8). Insensible a la casse : le code est
     * emis en majuscules mais saisi a la main par le filleul, souvent lu sur une capture
     * d'ecran. Refuser "a7k2mq" quand le code est "A7K2MQ" serait une friction gratuite.
     */
    Optional<User> findByReferralCodeIgnoreCase(String referralCode);

    boolean existsByReferralCode(String referralCode);

    long countByCreatedAtAfter(Instant since);

    /**
     * Comptes ayant deja depasse le stade de l'essai, dans un sens ou dans l'autre. Sert de
     * denominateur au taux de conversion : inclure les essais encore en cours ferait
     * mecaniquement chuter le taux affiche, puisqu'ils n'ont pas encore eu l'occasion de
     * convertir.
     */
    @Query("select count(u) from User u where u.subscriptionStatus in :statuses")
    long countByStatuses(@Param("statuses") java.util.Collection<SubscriptionStatus> statuses);
}
