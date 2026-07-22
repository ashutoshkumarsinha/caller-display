package com.example.enrollment.spml;

/**
 * Outcome of an SPML modify/add operation.
 */
public record SpmlResult(boolean success, int httpStatus, String errorCode, String detail) {

    public static SpmlResult ok() {
        return new SpmlResult(true, 200, null, null);
    }

    public static SpmlResult failure(int httpStatus, String errorCode, String detail) {
        return new SpmlResult(false, httpStatus, errorCode, detail);
    }

    public static SpmlResult notFound(String detail) {
        return failure(404, "subscriber_not_found", detail);
    }

    public static SpmlResult upstream(String detail) {
        return failure(502, "spml_upstream", detail);
    }
}
