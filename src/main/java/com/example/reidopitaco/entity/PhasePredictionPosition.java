package com.example.reidopitaco.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Um slot do Pick'em de tabela (ROUND_ROBIN/GROUPS): o time que o usuário colocou em
 * {@code predictedPosition} dentro do bloco ({@code group}, ou bloco único em ROUND_ROBIN com
 * {@code group == null}). A unicidade de posição e de time por bloco é reforçada por índices
 * únicos parciais no banco (V26).
 */
@Entity
@Table(name = "phase_prediction_positions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhasePredictionPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "phase_prediction_id", nullable = false, updatable = false)
    private PhasePrediction phasePrediction;

    /** Grupo do slot; {@code null} em fase ROUND_ROBIN (bloco único). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private PhaseGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "predicted_position", nullable = false)
    private int predictedPosition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
