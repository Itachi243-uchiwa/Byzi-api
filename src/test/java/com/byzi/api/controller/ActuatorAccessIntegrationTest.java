package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Les endpoints de supervision decrivent l'etat interne du serveur. Seule la sonde de sante
 * est publique - un load balancer doit pouvoir l'appeler sans identifiants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private String tokenFor(Role role) {
        User user = userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .role(role)
                .build());
        return jwtService.generateAccessToken(user.getId(), role);
    }

    @Test
    void healthProbeIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void metricsAreClosedToOrdinaryUsers() throws Exception {
        // Le point clef : ces endpoints tombaient dans le "anyRequest().authenticated()" et
        // etaient donc lisibles par n'importe quel utilisateur de l'app muni d'un JWT valide.
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + tokenFor(Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void metricsRemainReachableForAdmins() throws Exception {
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + tokenFor(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void metricsAreClosedToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
