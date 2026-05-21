package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class MatchGenerationException extends BusinessException {

    public MatchGenerationException(String detail) {
        super(detail, HttpStatus.CONFLICT);
    }
}
