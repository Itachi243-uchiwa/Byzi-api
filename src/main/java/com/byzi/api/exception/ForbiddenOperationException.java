package com.byzi.api.exception;

public class ForbiddenOperationException extends RuntimeException  {
    public ForbiddenOperationException(String message) {
        super(message);
    }

    public ForbiddenOperationException(String message, Throwable cause) {
        super(message, cause);
    }

}
