package com.example.futbet.entity;

import com.example.futbet.enums.MatchGenerationMode;
import com.example.futbet.enums.MatchLegMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Column(name = "groups_count")
    private Integer groupsCount;

    @Column(name = "qualifiers_per_group")
    private Integer qualifiersPerGroup;

    @Column(name = "plays_inside_group_only")
    private Boolean playsInsideGroupOnly;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_generation_mode", nullable = false, length = 15)
    private MatchGenerationMode matchGenerationMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_leg_mode", nullable = false, length = 15)
    private MatchLegMode matchLegMode;

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
