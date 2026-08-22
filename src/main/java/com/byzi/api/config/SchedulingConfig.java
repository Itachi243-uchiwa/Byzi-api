package com.byzi.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active l'infrastructure de taches planifiees de Spring (@Scheduled). Sans cette annotation,
 * un job comme RefreshTokenCleanupJob est declare mais ne s'execute jamais, silencieusement :
 * rien au demarrage ne signale que la purge censee tourner ne tourne pas.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
