package com.shanshui.apmserver.domain;

public record StackArtifactParseResponse(
        boolean success,
        String status) {

    public static StackArtifactParseResponse accepted() {
        return new StackArtifactParseResponse(true, "accepted");
    }

    public static StackArtifactParseResponse duplicate() {
        return new StackArtifactParseResponse(true, "duplicate");
    }
}
