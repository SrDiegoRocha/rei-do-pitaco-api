package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class TournamentMemberBannedException extends BusinessException {

    public TournamentMemberBannedException() {
        super("You are banned from this tournament", HttpStatus.FORBIDDEN);
    }
}
