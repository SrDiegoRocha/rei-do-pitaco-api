package com.example.reidopitaco.entity;

import com.example.reidopitaco.enums.MatchType;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Um slot do Pick'em de chaveamento (KNOCKOUT): o confronto que o usuário espera em
 * {@code (roundNumber, slotIndex)} da árvore e quem ele acha que avança. {@code roundNumber} é o
 * ordinal da rodada do bracket (1 = primeira rodada; em TWO_LEGGED as duas pernas contam como uma
 * rodada). {@code slotIndex} segue a ordem canônica dos confrontos dentro da rodada (mesma do
 * bracket: criação). A disputa de 3º lugar é um slot próprio com {@code matchType = THIRD_PLACE}
 * na rodada da final.
 */
@Entity
@Table(
        name = "phase_prediction_ties",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"phase_prediction_id", "round_number", "slot_index", "match_type"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhasePredictionTie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "phase_prediction_id", nullable = false, updatable = false)
    private PhasePrediction phasePrediction;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Column(name = "slot_index", nullable = false)
    private int slotIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 15)
    private MatchType matchType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "predicted_home_team_id", nullable = false)
    private Team predictedHomeTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "predicted_away_team_id", nullable = false)
    private Team predictedAwayTeam;

    /** Quem o usuário acha que avança; sempre um dos dois times do par (CHECK no banco). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "predicted_winner_team_id", nullable = false)
    private Team predictedWinnerTeam;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
