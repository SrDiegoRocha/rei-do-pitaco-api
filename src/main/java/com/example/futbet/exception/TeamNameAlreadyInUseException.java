package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class TeamNameAlreadyInUseException extends BusinessException {

    public TeamNameAlreadyInUseException() {
        super("You already have a team with this name", HttpStatus.CONFLICT);
    }
}
