package com.example.reidopitaco.exception;

import org.springframework.http.HttpStatus;

public class PhasePredictionLockedException extends BusinessException {

    public PhasePredictionLockedException(String detail) {
        super(detail, HttpStatus.CONFLICT);
    }
}
