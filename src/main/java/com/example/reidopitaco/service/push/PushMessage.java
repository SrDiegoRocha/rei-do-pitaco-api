package com.example.reidopitaco.service.push;

/**
 * Conteúdo de uma notificação push. `url` é o destino ao clicar (deep link /m/:matchId).
 */
public record PushMessage(String title, String body, String url) {
}
