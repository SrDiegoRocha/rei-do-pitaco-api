package com.example.reidopitaco.exception;

import org.springframework.http.HttpStatus;

public class PhasePredictionNotFoundException extends BusinessException {

    public PhasePredictionNotFoundException() {
        super("Phase prediction not found", HttpStatus.NOT_FOUND);
    }
}
