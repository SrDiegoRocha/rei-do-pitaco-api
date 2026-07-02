package com.example.reidopitaco.entity;

import com.example.reidopitaco.enums.MatchSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "predictions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "match_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false, updatable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false, updatable = false)
    private Match match;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "home_score", nullable = false)
    private int homeScore;

    @Column(name = "away_score", nullable = false)
    private int awayScore;

    /**
     * Placar palpitado da prorrogação (cumulativo, {@code >=} placar do tempo normal por time).
     * Só em mata-mata de jogo único quando o palpite do tempo normal é empate. {@code null} quando
     * o palpite não envolve prorrogação.
     */
    @Column(name = "home_extra_time_score")
    private Integer homeExtraTimeScore;

    @Column(name = "away_extra_time_score")
    private Integer awayExtraTimeScore;

    /**
     * Quem o palpiteiro acha que passa nos pênaltis (lado do confronto). Só preenchido em
     * palpite de empate em jogo único de mata-mata, ou na perna de volta de ida-e-volta.
     * {@code null} quando o palpite não envolve pênaltis.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_winner", length = 8)
    private MatchSide penaltyWinner;

    @Column(nullable = false)
    private int points;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
