package com.example.futbet.enums;

public enum TournamentStatus {
    DRAFT,
    OPEN,
    IN_PROGRESS,
    FINISHED;

    public boolean canTransitionTo(TournamentStatus target) {
        return switch (this) {
            case DRAFT -> target == OPEN;
            case OPEN -> target == IN_PROGRESS;
            case IN_PROGRESS -> target == FINISHED;
            case FINISHED -> false;
        };
    }
}
