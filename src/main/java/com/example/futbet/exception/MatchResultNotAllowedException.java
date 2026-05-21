package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class MatchResultNotAllowedException extends BusinessException {

    public MatchResultNotAllowedException(String detail) {
        super(detail, HttpStatus.CONFLICT);
    }
}
