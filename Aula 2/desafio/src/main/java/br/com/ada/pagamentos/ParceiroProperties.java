package br.com.ada.pagamentos;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração do parceiro lida do application.yml (prefixo "parceiro"). */
@ConfigurationProperties(prefix = "parceiro")
public record ParceiroProperties(String baseUrl, Duration connectTimeout, Duration readTimeout, long retryAfterSegundos) {}
