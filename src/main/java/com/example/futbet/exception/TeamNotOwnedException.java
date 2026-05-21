package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class TeamNotOwnedException extends BusinessException {

    public TeamNotOwnedException() {
        super("You can only add your own teams to a tournament", HttpStatus.FORBIDDEN);
    }
}
