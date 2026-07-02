package com.example.reidopitaco.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "tournament_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TournamentSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false, unique = true)
    private Tournament tournament;

    @Column(name = "win_points", nullable = false)
    private int winPoints;

    @Column(name = "draw_points", nullable = false)
    private int drawPoints;

    @Column(name = "loss_points", nullable = false)
    private int lossPoints;

    @Column(name = "exact_score_points", nullable = false)
    private int exactScorePoints;

    @Column(name = "winner_points", nullable = false)
    private int winnerPoints;

    @Column(name = "wrong_points", nullable = false)
    private int wrongPoints;

    // Componentes extras de mata-mata de jogo único, somados aos pontos do tempo normal.
    @Column(name = "extra_time_exact_score_points", nullable = false)
    private int extraTimeExactScorePoints;

    @Column(name = "extra_time_winner_points", nullable = false)
    private int extraTimeWinnerPoints;

    @Column(name = "penalty_winner_points", nullable = false)
    private int penaltyWinnerPoints;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
