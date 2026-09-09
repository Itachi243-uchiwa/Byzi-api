package com.byzi.api.controller.admin;

import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.admin.AdminExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

/**
 * Telechargement de l'export .xlsx (story 09.8).
 * <p>
 * L'autorisation est portee par {@code AdminExportService} : role finance, que {@code ADMIN}
 * satisfait via la hierarchie des roles. Le support consulte des comptes un par un pour traiter
 * des tickets ; extraire l'integralite de la base dans un fichier qui circule est un geste
 * different, et les separer est tout l'objet de la separation des roles (story 17.4).
 */
@Controller
@RequestMapping("/admin/export")
@RequiredArgsConstructor
public class AdminExportController {

    /** Type MIME officiel d'un classeur Open XML. */
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Meme repli que dans {@code AdminUserController} : un admin dont l'id ne se resout pas. */
    private static final UUID UNRESOLVED_ADMIN = new UUID(0L, 0L);

    private final AdminExportService exportService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<Resource> download(Authentication authentication) {
        String label = authentication.getName();
        UUID adminId = userRepository.findByEmail(label).map(User::getId).orElse(UNRESOLVED_ADMIN);
        byte[] workbook = exportService.buildWorkbook(adminId, label);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + exportService.fileName() + "\"")
                // Un export porte des donnees personnelles : aucun cache, ni navigateur ni
                // proxy. Il ne doit pas rester en clair dans un cache partage.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(workbook.length)
                .body(new ByteArrayResource(workbook));
    }
}
