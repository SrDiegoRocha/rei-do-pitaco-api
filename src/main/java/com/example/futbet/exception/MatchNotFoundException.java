package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class MatchNotFoundException extends BusinessException {

    public MatchNotFoundException() {
        super("Match not found", HttpStatus.NOT_FOUND);
    }
}
