package com.example.futbet.exception;

import org.springframework.http.HttpStatus;

public class CannotLeaveAsOwnerException extends BusinessException {

    public CannotLeaveAsOwnerException() {
        super("Tournament owner cannot leave their own tournament", HttpStatus.CONFLICT);
    }
}
