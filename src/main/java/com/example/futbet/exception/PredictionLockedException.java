package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class PredictionLockedException extends BusinessException {

    public PredictionLockedException(String detail) {
        super(detail, HttpStatus.CONFLICT);
    }
}
