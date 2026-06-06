package com.example.reidopitaco.service.push;

import com.example.reidopitaco.entity.PushSubscription;
import com.example.reidopitaco.repository.PushSubscriptionRepository;
import jakarta.annotation.PostConstruct;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Envia notificações Web Push (VAPID) para os dispositivos inscritos de um
 * conjunto de usuários. Sem chaves VAPID configuradas o envio fica desligado
 * (a aplicação sobe normalmente). Envios rodam de forma assíncrona para não
 * segurar a thread do agendador nem a resposta de quem lançou o resultado.
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    private final PushSubscriptionRepository subscriptionRepository;

    private final String publicKey;
    private final String privateKey;
    private final String subject;

    private PushService pushService;

    public WebPushService(
            PushSubscriptionRepository subscriptionRepository,
            @Value("${reidopitaco.push.vapid.public-key:}") String publicKey,
            @Value("${reidopitaco.push.vapid.private-key:}") String privateKey,
            @Value("${reidopitaco.push.vapid.subject:}") String subject
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.subject = subject;
    }

    @PostConstruct
    void init() {
        if (!isConfigured()) {
            log.warn("Web Push desativado: chaves VAPID ausentes (VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY).");
            return;
        }
        try {
            // A web-push pede o provider "BC" por nome (KeyFactory.getInstance(..., "BC")).
            // Ter o jar no classpath não basta: o provider precisa ser registrado em runtime.
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            this.pushService = new PushService(publicKey, privateKey, subject);
            log.info("Web Push ativado.");
        } catch (Exception e) {
            log.error("Falha ao inicializar Web Push — envio ficará desativado.", e);
            this.pushService = null;
        }
    }

    public boolean isEnabled() {
        return pushService != null;
    }

    private boolean isConfigured() {
        return StringUtils.hasText(publicKey)
                && StringUtils.hasText(privateKey)
                && StringUtils.hasText(subject);
    }

    /**
     * Dispara a mensagem para todos os dispositivos dos usuários informados.
     * Inscrições expiradas (404/410) são removidas. Assíncrono e tolerante a falhas.
     */
    @Async
    @Transactional
    public void sendToUsers(Collection<Long> userIds, PushMessage message) {
        if (!isEnabled() || userIds == null || userIds.isEmpty()) {
            return;
        }
        List<PushSubscription> subscriptions = subscriptionRepository.findAllByUserIdIn(userIds);
        if (subscriptions.isEmpty()) {
            return;
        }
        byte[] payload = serialize(message);
        List<Long> dead = new ArrayList<>();
        for (PushSubscription sub : subscriptions) {
            try {
                Notification notification = new Notification(
                        sub.getEndpoint(), sub.getP256dh(), sub.getAuth(), payload
                );
                HttpResponse response = pushService.send(notification);
                int status = response.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    dead.add(sub.getId());
                } else if (status >= 400) {
                    log.warn("Push falhou (status {}) para subscription {}.", status, sub.getId());
                }
            } catch (Exception e) {
                log.warn("Erro ao enviar push para subscription {}: {}", sub.getId(), e.getMessage());
            }
        }
        if (!dead.isEmpty()) {
            subscriptionRepository.deleteAllById(dead);
            log.info("Removidas {} inscrições push expiradas.", dead.size());
        }
    }

    /**
     * Monta o payload no formato que o service worker do Angular (ngsw) entende.
     * JSON construído à mão (estrutura fixa) para não depender de bean/versão do
     * Jackson — o Boot 4 migrou para o Jackson 3 e o mapper antigo não é um bean.
     */
    private byte[] serialize(PushMessage message) {
        String title = escapeJson(message.title());
        String body = escapeJson(message.body());
        String url = escapeJson(message.url());
        String json = "{\"notification\":{"
                + "\"title\":\"" + title + "\","
                + "\"body\":\"" + body + "\","
                + "\"icon\":\"/icons/icon-192.png\","
                + "\"data\":{"
                + "\"url\":\"" + url + "\","
                + "\"onActionClick\":{\"default\":{"
                + "\"operation\":\"navigateLastFocusedOrOpen\","
                + "\"url\":\"" + url + "\"}}"
                + "}}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Escapa uma string para uso seguro dentro de aspas em JSON. */
    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
