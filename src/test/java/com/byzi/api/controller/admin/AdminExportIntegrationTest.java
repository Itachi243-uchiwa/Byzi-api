package com.byzi.api.controller.admin;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export .xlsx du back-office (story 09.8).
 * <p>
 * Deux choses a proteger : que le fichier soit un vrai classeur exploitable (un export qui ne
 * s'ouvre pas ne sert a rien), et que le role support n'y ait pas acces - extraire toute la base
 * dans un fichier qui circule n'est pas le meme geste que consulter un compte pour un ticket.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminExportIntegrationTest {

    private static final String ADMIN_EMAIL = "export-admin@exemple.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;

    private void givenAnAccount() {
        userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .email("abonne-" + UUID.randomUUID() + "@exemple.com")
                .role(Role.USER)
                .subscriptionStatus(SubscriptionStatus.ACTIVE)
                .build());
    }

    @Test
    void producesAReadableWorkbookWithTheFourSheets() throws Exception {
        givenAnAccount();

        MvcResult result = mockMvc.perform(get("/admin/export").with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).isNotEmpty();

        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).contains("dopamyn-export-").contains(".xlsx");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .as("un export de donnees personnelles ne se met pas en cache")
                .isEqualTo("no-store");

        // Relire le fichier avec POI est la seule verification qui prouve qu'il s'ouvrira :
        // un tableau d'octets non vide ne dit rien d'un classeur valide.
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Utilisateurs");
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("Abonnements en cours");
            assertThat(workbook.getSheetAt(2).getSheetName()).isEqualTo("Activations");
            assertThat(workbook.getSheetAt(3).getSheetName()).isEqualTo("Annulations");

            // L'en-tete est fige et renseigne : c'est ce qui rend le fichier lisible sans
            // documentation a cote.
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Identifiant");
        }
    }

    /**
     * Le garde-fou de separation des roles. Le support traite des tickets compte par compte ;
     * il n'a aucune raison de pouvoir emporter la base entiere.
     */
    @Test
    void theSupportRoleCannotExport() throws Exception {
        mockMvc.perform(get("/admin/export").with(user("support@exemple.com").roles("ADMIN_SUPPORT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theFinanceRoleCanExport() throws Exception {
        mockMvc.perform(get("/admin/export").with(user("finance@exemple.com").roles("ADMIN_FINANCE")))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCallersAreRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/admin/export")).andExpect(status().is3xxRedirection());
    }
}
