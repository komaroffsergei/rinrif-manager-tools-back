package ru.reinform.rinrif.managertools.atr2spec;

public class Atr2SpecException extends RuntimeException {
    private final int status;

    public Atr2SpecException(String message) {
        this(message, 500, null);
    }

    public Atr2SpecException(String message, int status) {
        this(message, status, null);
    }

    public Atr2SpecException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
