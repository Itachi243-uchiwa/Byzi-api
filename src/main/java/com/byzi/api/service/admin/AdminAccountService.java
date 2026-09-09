package com.byzi.api.service.admin;

import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changement de mot de passe d'un administrateur, par lui-meme.
 * <p>
 * Volontairement separe d'{@code AdminUserService}, qui agit SUR des comptes utilisateurs : ici
 * l'administrateur agit sur le sien. Melanger les deux ferait cohabiter une methode sans
 * {@code @PreAuthorize} avec des methodes qui en portent une - une invitation a l'erreur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAccountService {

    /** Meme plancher que le bootstrap : ce compte ouvre la suppression definitive de comptes. */
    static final int MINIMUM_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditService auditService;

    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword, String confirmPassword) {
        User admin = userRepository.findByEmail(email)
                // Message deliberement identique a celui d'un mot de passe faux : distinguer les
                // deux dirait a un attaquant si l'adresse existe.
                .orElseThrow(() -> new IllegalArgumentException("Identifiants incorrects."));

        if (admin.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, admin.getPasswordHash())) {
            throw new IllegalArgumentException("Identifiants incorrects.");
        }
        if (newPassword == null || newPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Le nouveau mot de passe doit faire au moins " + MINIMUM_PASSWORD_LENGTH + " caracteres.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("Les deux saisies ne correspondent pas.");
        }
        if (passwordEncoder.matches(newPassword, admin.getPasswordHash())) {
            throw new IllegalArgumentException("Le nouveau mot de passe doit etre different de l'ancien.");
        }

        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(admin);

        // Trace d'audit sans le moindre element du secret : QUI et QUAND, jamais QUOI.
        auditService.record(admin.getId(), admin.getEmail(), AdminAuditService.ACTION_CHANGE_PASSWORD,
                admin.getId(), "Changement de mot de passe par le titulaire du compte");
        log.info("Mot de passe administrateur modifie (adminId={})", admin.getId());
    }
}
