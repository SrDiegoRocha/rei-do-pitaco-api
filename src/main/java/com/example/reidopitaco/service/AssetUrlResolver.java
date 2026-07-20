package com.example.reidopitaco.service;

import com.example.reidopitaco.config.AssetsProperties;
import org.springframework.stereotype.Service;

/**
 * Resolve o {@code badgeUrl} persistido para a URL absoluta que o cliente consome.
 *
 * <p>Times do sistema guardam caminho <b>relativo</b> no banco (ex.: {@code /logos/brazil/flamengo.png})
 * para o banco não ficar amarrado a um domínio — aqui ele ganha o prefixo configurável
 * ({@code reidopitaco.assets.base-url}). URLs absolutas (times de usuário) passam intactas.
 * Mesmo padrão do {@code AvatarService}: o front sempre recebe URL pronta para o {@code <img>}.
 */
@Service
public class AssetUrlResolver {

    private final AssetsProperties properties;

    public AssetUrlResolver(AssetsProperties properties) {
        this.properties = properties;
    }

    public String resolve(String badgeUrl) {
        if (badgeUrl == null || badgeUrl.isBlank()) {
            return badgeUrl;
        }
        return badgeUrl.startsWith("/") ? properties.baseUrl() + badgeUrl : badgeUrl;
    }
}
