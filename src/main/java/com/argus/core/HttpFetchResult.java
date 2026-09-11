package com.argus.core;

record HttpFetchResult(int statusCode, String body) {
    boolean isSuccess() {
        return statusCode >= 200 && statusCode <= 299;
    }
}
