package com.buzi.api.security.apple;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "byzi.security.apple")
public record AppleAuthProperties (
    @NotBlank
    String issuer,

    @NotBlank
    String audience,

    @NotBlank
    String jwksUrl

    ){
}
