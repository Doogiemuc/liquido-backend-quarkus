package org.liquido.security;

/** Optimized small response DTO for LoginRestAPI#checkLoginEmail() */
public record EmailExistsRec(boolean exists, boolean hasWebAuthn) {}