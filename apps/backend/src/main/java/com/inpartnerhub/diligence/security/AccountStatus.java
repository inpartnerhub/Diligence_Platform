package com.inpartnerhub.diligence.security;

import java.util.UUID;

/**
 * Whether an account was disabled by an administrator (#173). Access tokens stay valid until they expire, so every
 * request is checked against this; implemented by the admin module, which owns the accounts.
 */
public interface AccountStatus {

    boolean isDisabled(UUID userId);
}
