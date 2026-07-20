package com.example.reidopitaco.entity;

import com.example.reidopitaco.enums.TournamentPhaseType;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pick'em de fase: palpite de alto nível de um usuário sobre o desfecho de uma fase inteira,
 * registrado antes de a fase começar. O detalhe fica nas coleções filhas conforme o tipo da fase
 * ({@link PhasePredictionPosition} para tabela/grupos, {@link PhasePredictionTie} para mata-mata).
 * {@code points} é materializado e recomputado quando os resultados da fase mudam — é o valor que
 * o ranking soma.
 */
@Entity
@Table(
        name = "phase_predictions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "phase_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhasePrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false, updatable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "phase_id", nullable = false, updatable = false)
    private TournamentPhase phase;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** Snapshot do tipo da fase no momento do palpite — decide qual coleção filha se aplica. */
    @Enumerated(EnumType.STRING)
    @Column(name = "phase_type", nullable = false, length = 15)
    private TournamentPhaseType phaseType;

    /** Total do Pick'em, recomputado a cada resultado da fase. Fonte que o ranking soma. */
    @Column(nullable = false)
    private int points;

    /** Última vez que a pontuação foi (re)calculada; {@code null} = ainda não pontuado. */
    @Column(name = "scored_at")
    private Instant scoredAt;

    @OneToMany(mappedBy = "phasePrediction", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PhasePredictionPosition> positions = new ArrayList<>();

    @OneToMany(mappedBy = "phasePrediction", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PhasePredictionTie> ties = new ArrayList<>();

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
