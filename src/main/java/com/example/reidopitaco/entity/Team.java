package com.example.reidopitaco.entity;

import com.example.reidopitaco.enums.TeamType;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teams")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    // Nullable: times padrão do sistema (system = true) não têm dono.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", updatable = false)
    private User owner;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "short_name", length = 5)
    private String shortName;

    @Column(name = "badge_url", length = 500)
    private String badgeUrl;

    @Column(name = "primary_color", nullable = false, length = 7)
    private String primaryColor;

    @Column(name = "secondary_color", nullable = false, length = 7)
    private String secondaryColor;

    // Time padrão do sistema: visível a todos, não editável/deletável pelo usuário.
    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_type", nullable = false, length = 20)
    private TeamType teamType;

    // Código ISO/flagicons (ex.: "br", "gb-eng"). Seleções: bandeira do país; clubes do
    // sistema: país da liga (bandeirinha ao lado do nome da liga no front).
    @Column(name = "country_code", length = 10)
    private String countryCode;

    // Liga nacional — só em clubes do sistema (NULL em times de usuário e seleções).
    @Column(name = "league_slug", length = 60)
    private String leagueSlug;

    @Column(name = "league_name", length = 80)
    private String leagueName;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (teamType == null) {
            teamType = TeamType.CLUB;
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        active = true;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
