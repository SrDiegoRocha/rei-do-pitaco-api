package com.example.futbet.entity;

import com.example.futbet.enums.ZoneSelectionMode;
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
        name = "tournament_zones",
        uniqueConstraints = @UniqueConstraint(columnNames = {"phase_id", "position"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TournamentZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "phase_id", nullable = false, updatable = false)
    private TournamentPhase phase;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "from_position", nullable = false)
    private int fromPosition;

    @Column(name = "to_position", nullable = false)
    private int toPosition;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_mode", nullable = false, length = 15)
    private ZoneSelectionMode selectionMode;

    @Column(name = "best_ranked_count")
    private Integer bestRankedCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "next_phase_id")
    private TournamentPhase nextPhase;

    @Column(nullable = false)
    private int position;

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
