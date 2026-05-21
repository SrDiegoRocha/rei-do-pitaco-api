package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class PhaseHasMatchesException extends BusinessException {

    public PhaseHasMatchesException(String resource) {
        super("Cannot remove " + resource + " because it has matches attached", HttpStatus.CONFLICT);
    }
}
