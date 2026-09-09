package com.byzi.api.service.admin;

import com.byzi.api.domain.SubscriptionEvent;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Export .xlsx du back-office (story 09.8) : comptes, abonnements, activations, annulations.
 * <p>
 * <b>Un seul classeur, quatre feuilles</b>, et non quatre fichiers. Les trois feuilles
 * d'abonnement se lisent les unes contre les autres — "combien d'activations ce mois-ci, et
 * combien d'annulations en face" est la question qu'on se pose reellement, et elle demande de
 * pouvoir passer d'un onglet a l'autre dans le meme fichier.
 *
 * <h2>Ce qui n'y figure pas, et pourquoi</h2>
 * Ni {@code passwordHash}, ni {@code appleSub}, ni {@code referralCode}. Un export circule : il
 * part par e-mail, atterrit sur un poste, se retrouve dans une piece jointe. Il ne doit donc
 * porter que ce qui sert a la question posee — la sante commerciale du produit — jamais un
 * secret ni un identifiant qui permettrait d'usurper un compte. L'{@code appleSub} en
 * particulier est l'identite Apple de la personne : il n'a aucune raison de quitter la base.
 *
 * <h2>Memoire</h2>
 * {@link SXSSFWorkbook} et non {@code XSSFWorkbook} : le classeur est ecrit en flux, avec une
 * fenetre de lignes bornee. Le cout memoire ne depend donc pas du nombre de comptes — meme
 * principe que le cache borne du limiteur de debit. Les fichiers temporaires que POI cree sont
 * explicitement supprimes a la fin ({@code dispose}), sinon un export par jour finit par
 * remplir le disque du serveur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminExportService {

    /** Fenetre de lignes gardees en memoire par SXSSF avant ecriture sur disque. */
    private static final int ROW_WINDOW = 200;

    /**
     * Les statuts qui signent une <b>activation</b>. {@code GRACE_PERIOD} n'en fait pas partie :
     * c'est la prolongation d'un acces existant pendant un incident de paiement, pas une
     * nouvelle entree. Le compter comme une activation gonflerait artificiellement le chiffre.
     */
    private static final Set<SubscriptionStatus> ACTIVATIONS =
            Set.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIAL);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneId.systemDefault());

    private final UserRepository userRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final AdminAuditService auditService;

    public String fileName() {
        return "dopamyn-export-" + FILE_STAMP.format(Instant.now()) + ".xlsx";
    }

    /**
     * Construit le classeur en memoire et rend ses octets.
     * <p>
     * {@code readOnly} : l'export ne modifie rien, mais il LIT tout. La transaction en lecture
     * seule garde les entites detachees du contexte de persistance et evite qu'un parcours de
     * plusieurs milliers de lignes ne les accumule toutes comme "sales".
     */
    @PreAuthorize("hasRole('ADMIN_FINANCE')")
    @Transactional(readOnly = true)
    public byte[] buildWorkbook(java.util.UUID adminId, String adminLabel) {
        List<User> users = userRepository.findAll();
        List<SubscriptionEvent> events = subscriptionEventRepository.findAll().stream()
                .sorted(Comparator.comparing(SubscriptionEvent::getOccurredAt).reversed())
                .toList();

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle header = headerStyle(workbook);

            writeUsers(workbook, header, users);
            writeSubscribers(workbook, header, users);
            writeEvents(workbook, header, "Activations", events,
                    e -> ACTIVATIONS.contains(e.getResultingStatus()));
            writeEvents(workbook, header, "Annulations", events,
                    e -> e.getResultingStatus() == SubscriptionStatus.EXPIRED);

            workbook.write(out);
            // Un export laisse une trace : c'est une extraction de donnees personnelles, et
            // savoir qui l'a declenchee est exactement ce que le journal est la pour dire.
            auditService.record(adminId, adminLabel, AdminAuditService.ACTION_EXPORT, null,
                    "Export .xlsx : " + users.size() + " compte(s), " + events.size() + " evenement(s)");
            log.info("Export admin genere ({} comptes, {} evenements)", users.size(), events.size());
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Generation de l'export .xlsx impossible", e);
        }
        // `dispose()` est appele par `close()` de SXSSFWorkbook sur les versions recentes ;
        // le try-with-resources garantit donc la suppression des fichiers temporaires.
    }

    // MARK: Les feuilles

    private void writeUsers(Workbook workbook, CellStyle header, List<User> users) {
        Sheet sheet = workbook.createSheet("Utilisateurs");
        writeHeader(sheet, header, "Identifiant", "E-mail", "Prenom", "Role",
                "Statut abonnement", "Acces jusqu'au", "Derniere connexion");
        int r = 1;
        for (User user : users) {
            Row row = sheet.createRow(r++);
            int c = 0;
            cell(row, c++, user.getId().toString());
            // L'e-mail peut etre absent : Sign in with Apple avec l'option "Masquer mon
            // adresse". Une cellule vide dit la verite, "-" laisserait croire a une donnee.
            cell(row, c++, user.getEmail());
            cell(row, c++, user.getGivenName());
            cell(row, c++, user.getRole() == null ? null : user.getRole().name());
            cell(row, c++, user.getSubscriptionStatus() == null ? null : user.getSubscriptionStatus().name());
            cell(row, c++, format(user.getSubscriptionExpiresAt()));
            cell(row, c, format(user.getLastLoginAt()));
        }
    }

    /**
     * Les comptes qui ont un acces payant en cours. C'est la feuille qu'on ouvre en premier :
     * "combien de gens paient aujourd'hui" ne doit pas demander de filtrer soi-meme.
     */
    private void writeSubscribers(Workbook workbook, CellStyle header, List<User> users) {
        Sheet sheet = workbook.createSheet("Abonnements en cours");
        writeHeader(sheet, header, "Identifiant", "E-mail", "Statut", "Acces jusqu'au");
        int r = 1;
        for (User user : users) {
            SubscriptionStatus status = user.getSubscriptionStatus();
            if (status == null || status == SubscriptionStatus.EXPIRED) {
                continue;
            }
            Row row = sheet.createRow(r++);
            cell(row, 0, user.getId().toString());
            cell(row, 1, user.getEmail());
            cell(row, 2, status.name());
            cell(row, 3, format(user.getSubscriptionExpiresAt()));
        }
    }

    private void writeEvents(Workbook workbook, CellStyle header, String name,
                             List<SubscriptionEvent> events,
                             java.util.function.Predicate<SubscriptionEvent> keep) {
        Sheet sheet = workbook.createSheet(name);
        writeHeader(sheet, header, "Date", "Compte", "E-mail", "Type d'evenement",
                "Statut resultant", "Acces jusqu'au");
        int r = 1;
        for (SubscriptionEvent event : events) {
            if (!keep.test(event)) {
                continue;
            }
            User user = event.getUser();
            Row row = sheet.createRow(r++);
            int c = 0;
            cell(row, c++, format(event.getOccurredAt()));
            cell(row, c++, user == null ? null : user.getId().toString());
            cell(row, c++, user == null ? null : user.getEmail());
            cell(row, c++, event.getEventType());
            cell(row, c++, event.getResultingStatus() == null ? null : event.getResultingStatus().name());
            cell(row, c, format(event.getExpiresAt()));
        }
    }

    // MARK: Mise en forme

    private CellStyle headerStyle(Workbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        bold.setColor(IndexedColors.WHITE.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private void writeHeader(Sheet sheet, CellStyle style, String... titles) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            row.createCell(i).setCellValue(titles[i]);
            row.getCell(i).setCellStyle(style);
            // Largeur posee a la main : `autoSizeColumn` exige de garder toutes les lignes en
            // memoire, ce qui annulerait l'interet du mode flux.
            sheet.setColumnWidth(i, columnWidth(titles[i]));
        }
        sheet.createFreezePane(0, 1);
    }

    /** 36 caracteres pour un identifiant (UUID), 22 sinon. Unite POI : 1/256 de caractere. */
    private int columnWidth(String title) {
        boolean wide = title.startsWith("Identifiant") || title.equals("Compte") || title.equals("E-mail");
        return (wide ? 38 : 22) * 256;
    }

    private void cell(Row row, int index, String value) {
        if (value != null && !value.isBlank()) {
            row.createCell(index).setCellValue(value);
        }
    }

    /** Date lisible dans le fuseau du serveur. Une cellule vide plutot qu'un tiret. */
    private String format(Instant instant) {
        return instant == null ? null : STAMP.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }
}
