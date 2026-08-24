package com.byzi.api.dto.referral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param code code saisi par le filleul. La casse et les espaces sont normalises cote
 *             serveur : le code est le plus souvent recopie a la main depuis une capture
 *             d'ecran ou un message.
 */
public record ReferralRedeemRequest(
        @NotBlank
        @Size(max = 10)
        String code
) {
}
