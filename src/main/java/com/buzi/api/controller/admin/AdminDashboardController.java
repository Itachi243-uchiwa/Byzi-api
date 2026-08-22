package com.buzi.api.controller.admin;

import com.buzi.api.service.admin.AdminAuditService;
import com.buzi.api.service.admin.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;
    private final AdminAuditService auditService;

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("kpi", dashboardService.computeKpi());
        model.addAttribute("recentActions", auditService.list(
                org.springframework.data.domain.PageRequest.of(0, 8)).getContent());
        return "admin/dashboard";
    }

    @GetMapping("/audit")
    public String audit(@PageableDefault(size = 40) Pageable pageable, Model model) {
        model.addAttribute("entries", auditService.list(pageable));
        return "admin/audit";
    }
}
