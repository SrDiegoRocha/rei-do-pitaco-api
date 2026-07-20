package com.example.reidopitaco.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração dos assets estáticos servidos pela própria API (escudos em {@code /logos/**}).
 *
 * <p>{@code baseUrl} prefixa os {@code badge_url} relativos dos times do sistema na leitura
 * (ver {@code AssetUrlResolver}); {@code logosDir} é a pasta no disco de onde os PNGs são
 * servidos. Ajustável via env: {@code ASSETS_BASE_URL}, {@code LOGOS_DIR}.
 */
@ConfigurationProperties(prefix = "reidopitaco.assets")
public record AssetsProperties(
        String baseUrl,
        String logosDir
) {
    public AssetsProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        // Sem barra no fim: o resolver concatena com paths que já começam com "/".
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (logosDir == null || logosDir.isBlank()) {
            logosDir = "./logos";
        }
    }
}
