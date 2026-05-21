package com.example.futbet.exception;

import com.example.futbet.enums.TournamentStatus;
import org.springframework.http.HttpStatus;

public class InvalidStatusTransitionException extends BusinessException {

    public InvalidStatusTransitionException(TournamentStatus from, TournamentStatus to) {
        super("Cannot transition tournament from " + from + " to " + to, HttpStatus.CONFLICT);
    }
}
