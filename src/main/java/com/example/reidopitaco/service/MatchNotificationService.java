package com.example.reidopitaco.service;

import com.example.reidopitaco.entity.Match;
import com.example.reidopitaco.repository.MatchRepository;
import com.example.reidopitaco.repository.PredictionRepository;
import com.example.reidopitaco.repository.TournamentMemberRepository;
import com.example.reidopitaco.service.push.PushMessage;
import com.example.reidopitaco.service.push.WebPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Dispara as notificações push das partidas:
 * <ul>
 *   <li>24h antes — para todos os membros ativos do torneio;</li>
 *   <li>4h e 1h antes — só para membros ativos que ainda não palpitaram;</li>
 *   <li>resultado lançado — para todos que palpitaram (acionado pelo MatchService).</li>
 * </ul>
 * Cada faixa usa uma flag na partida para garantir um único envio. A varredura
 * trabalha em bandas de tempo para não mandar "em 24 horas" numa partida que já
 * está a poucas horas do início.
 */
@Service
public class MatchNotificationService {

    private static final Logger log = LoggerFactory.getLogger(MatchNotificationService.class);

    private static final Duration H1 = Duration.ofHours(1);
    private static final Duration H4 = Duration.ofHours(4);
    private static final Duration H24 = Duration.ofHours(24);

    private final MatchRepository matchRepository;
    private final TournamentMemberRepository memberRepository;
    private final PredictionRepository predictionRepository;
    private final WebPushService webPushService;

    /**
     * Largura da janela de disparo dos avisos de 24h e 4h: cada aviso só sai quando a
     * partida está "se aproximando" do marco (entre {@code marco - margem} e {@code marco}),
     * e não em toda a faixa até o marco anterior. Assim uma partida a ~1h não recebe o
     * texto "em 4 horas". A margem acompanha o intervalo da varredura para que ao menos
     * uma varredura caia dentro da janela; a flag por faixa evita disparo duplicado.
     */
    private final Duration reminderMargin;

    public MatchNotificationService(
            MatchRepository matchRepository,
            TournamentMemberRepository memberRepository,
            PredictionRepository predictionRepository,
            WebPushService webPushService,
            @org.springframework.beans.factory.annotation.Value(
                    "${reidopitaco.push.scan-interval-ms:300000}") long scanIntervalMs
    ) {
        this.matchRepository = matchRepository;
        this.memberRepository = memberRepository;
        this.predictionRepository = predictionRepository;
        this.webPushService = webPushService;
        // Dobro do intervalo da varredura: cobre jitter do agendador sem perder a janela.
        this.reminderMargin = Duration.ofMillis(scanIntervalMs).multipliedBy(2);
    }

    @Scheduled(fixedDelayString = "${reidopitaco.push.scan-interval-ms:300000}", initialDelay = 30000)
    @Transactional
    public void scanAndNotify() {
        if (!webPushService.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        try {
            process24h(now);
            process4h(now);
            process1h(now);
        } catch (Exception e) {
            log.error("Falha na varredura de notificações de partidas.", e);
        }
    }

    private void process24h(Instant now) {
        // Janela estreita: partidas a ~24h (entre 24h - margem e 24h).
        List<Match> due = matchRepository.findDueForReminder24h(
                now.plus(H24).minus(reminderMargin), now.plus(H24));
        for (Match m : due) {
            List<Long> audience = memberRepository.findActiveMemberUserIds(
                    m.getPhase().getTournament().getId()
            );
            webPushService.sendToUsers(audience, new PushMessage(
                    "⚽ Partida em 24 horas",
                    teams(m) + " acontece em 24 horas.",
                    urlFor(m)
            ));
            m.setNotified24h(true);
        }
    }

    private void process4h(Instant now) {
        // Janela estreita: partidas a ~4h (entre 4h - margem e 4h). Não cobre a faixa
        // até 1h — partida a ~1h fica para o aviso de 1h, com o texto correto.
        List<Match> due = matchRepository.findDueForReminder4h(
                now.plus(H4).minus(reminderMargin), now.plus(H4));
        for (Match m : due) {
            List<Long> audience = memberRepository.findActiveMembersWithoutPrediction(
                    m.getPhase().getTournament().getId(), m.getId()
            );
            webPushService.sendToUsers(audience, new PushMessage(
                    "⏰ Faltam 4 horas!",
                    teams(m) + " começa em 4 horas e você ainda não palpitou!",
                    urlFor(m)
            ));
            m.setNotified4h(true);
        }
    }

    private void process1h(Instant now) {
        List<Match> due = matchRepository.findDueForReminder1h(now, now.plus(H1));
        for (Match m : due) {
            List<Long> audience = memberRepository.findActiveMembersWithoutPrediction(
                    m.getPhase().getTournament().getId(), m.getId()
            );
            webPushService.sendToUsers(audience, new PushMessage(
                    "🔥 Quase começando!",
                    teams(m) + " está prestes a começar. Não fique de fora, dê seu pitaco!",
                    urlFor(m)
            ));
            m.setNotified1h(true);
        }
    }

    /**
     * Avisa quem palpitou que o resultado saiu. Chamado dentro da transação do
     * {@code setResult}, então a flag persiste junto com o resultado.
     */
    @Transactional
    public void notifyResultAvailable(Match match) {
        if (!webPushService.isEnabled() || match.isNotifiedResult()) {
            return;
        }
        List<Long> audience = predictionRepository.findUserIdsByMatchId(match.getId());
        if (!audience.isEmpty()) {
            webPushService.sendToUsers(audience, new PushMessage(
                    "🏁 Resultado disponível",
                    "O resultado de " + teams(match) + " já saiu. Confira sua pontuação!",
                    urlFor(match)
            ));
        }
        match.setNotifiedResult(true);
    }

    private String teams(Match m) {
        return m.getHomeTeam().getName() + " x " + m.getAwayTeam().getName();
    }

    private String urlFor(Match m) {
        return "/m/" + m.getPublicId();
    }
}
