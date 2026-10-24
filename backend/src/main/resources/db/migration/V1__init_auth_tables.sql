CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE auth.users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT chk_users_email_normalized CHECK (
        email = LOWER(BTRIM(email)) AND CHAR_LENGTH(email) > 0
    ),
    CONSTRAINT chk_users_password_hash_not_blank CHECK (password_hash ~ '[^[:space:]]'),
    CONSTRAINT chk_users_display_name CHECK (
        CHAR_LENGTH(display_name) BETWEEN 2 AND 50
        AND display_name = BTRIM(display_name)
        AND display_name ~ '[^[:space:]]'
    )
);

CREATE TABLE auth.refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_rotated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_refresh_sessions_user FOREIGN KEY (user_id)
        REFERENCES auth.users (id) ON DELETE CASCADE,
    CONSTRAINT chk_refresh_sessions_rotation_time CHECK (last_rotated_at >= created_at),
    CONSTRAINT chk_refresh_sessions_expiry CHECK (expires_at > last_rotated_at),
    CONSTRAINT chk_refresh_sessions_revocation_time CHECK (
        revoked_at IS NULL OR revoked_at >= created_at
    )
);

CREATE INDEX idx_refresh_sessions_user_id ON auth.refresh_sessions (user_id);
CREATE INDEX idx_refresh_sessions_expires_at ON auth.refresh_sessions (expires_at);
CREATE INDEX idx_refresh_sessions_revoked_at ON auth.refresh_sessions (revoked_at)
    WHERE revoked_at IS NOT NULL;

CREATE TABLE auth.refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_refresh_tokens_session FOREIGN KEY (session_id)
        REFERENCES auth.refresh_sessions (id) ON DELETE CASCADE,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT chk_refresh_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_refresh_tokens_expiry CHECK (expires_at > issued_at),
    CONSTRAINT chk_refresh_tokens_consumption_time CHECK (
        consumed_at IS NULL OR consumed_at >= issued_at
    )
);

CREATE INDEX idx_refresh_tokens_session_id ON auth.refresh_tokens (session_id);
CREATE UNIQUE INDEX uk_refresh_tokens_unconsumed_session ON auth.refresh_tokens (session_id)
    WHERE consumed_at IS NULL;
