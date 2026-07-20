package com.example.reidopitaco.entity;

import com.example.reidopitaco.enums.MatchGenerationMode;
import com.example.reidopitaco.enums.MatchLegMode;
import com.example.reidopitaco.enums.TournamentPhaseType;
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
        name = "tournament_phases",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tournament_id", "position"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TournamentPhase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false, updatable = false)
    private Tournament tournament;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase_type", nullable = false, length = 15)
    private TournamentPhaseType phaseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_leg_mode", nullable = false, length = 15)
    private MatchLegMode matchLegMode;

    /**
     * Modo de pernas da <b>rodada final</b> (final + disputa de 3º lugar), decidido pelo admin
     * independentemente do {@link #matchLegMode} da fase. {@code null} = herda o modo da fase.
     * Só se aplica a fases KNOCKOUT.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "final_leg_mode", length = 15)
    private MatchLegMode finalLegMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_generation_mode", nullable = false, length = 15)
    private MatchGenerationMode matchGenerationMode;

    @Column(name = "plays_inside_group_only")
    private Boolean playsInsideGroupOnly;

    @Column(name = "has_third_place", nullable = false)
    private boolean hasThirdPlace;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

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
