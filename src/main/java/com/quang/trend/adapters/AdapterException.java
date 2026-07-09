package com.quang.trend.adapters;

/** Unchecked failure from a source adapter (HTTP error, bad payload, interrupt). */
public class AdapterException extends RuntimeException {
    public AdapterException(String message) {
        super(message);
    }
    public AdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
