package com.mongle.backend.migration;

/** Only a fixed code is allowed across the deployment log boundary. */
public final class MigrationFailure extends RuntimeException {
    public MigrationFailure(String code) { super(code); }
}
