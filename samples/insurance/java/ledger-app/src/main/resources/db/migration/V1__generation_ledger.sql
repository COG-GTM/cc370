-- Generation ledger. Every byte of the legacy records is stored verbatim (BYTEA); the database
-- enforces record lengths, one fenced pending writer per generation, contiguous ordinals, an
-- expected-parent CAS on the namespace pointer and the immutability of published rows.

CREATE SEQUENCE generation_fence_seq;

CREATE TABLE generation (
    id                 BIGSERIAL PRIMARY KEY,
    namespace          TEXT        NOT NULL,
    name               TEXT        NOT NULL,
    parent_id          BIGINT      REFERENCES generation (id),
    status             TEXT        NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'DISCARDED')),
    fence              BIGINT      NOT NULL,
    policies_count     INTEGER     NOT NULL CHECK (policies_count BETWEEN 0 AND 512),
    last_ordinal       BIGINT      NOT NULL DEFAULT 0 CHECK (last_ordinal >= 0),
    typed_requests     INTEGER     NOT NULL DEFAULT 0,
    raw_requests       INTEGER     NOT NULL DEFAULT 0,
    seed_polin_sha256  TEXT        NOT NULL,
    seed               BYTEA       NOT NULL CHECK (octet_length(seed) % 128 = 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (namespace, name)
);

CREATE TABLE namespace (
    name                   TEXT PRIMARY KEY,
    current_generation_id  BIGINT REFERENCES generation (id)
);

CREATE TABLE generation_entry (
    generation_id  BIGINT  NOT NULL REFERENCES generation (id),
    ordinal        BIGINT  NOT NULL CHECK (ordinal >= 1),
    request        BYTEA   NOT NULL CHECK (octet_length(request) = 40),
    result         BYTEA   NOT NULL CHECK (octet_length(result) = 96),
    typed          BOOLEAN NOT NULL,
    successor      BYTEA   CHECK (successor IS NULL OR octet_length(successor) = 128),
    PRIMARY KEY (generation_id, ordinal)
);

CREATE TABLE policy_state (
    generation_id  BIGINT  NOT NULL REFERENCES generation (id),
    policy_id      BYTEA   NOT NULL CHECK (octet_length(policy_id) = 8),
    position       INTEGER NOT NULL CHECK (position >= 0),
    bytes          BYTEA   NOT NULL CHECK (octet_length(bytes) = 128),
    PRIMARY KEY (generation_id, policy_id),
    UNIQUE (generation_id, position)
);

CREATE TABLE generation_output (
    generation_id  BIGINT PRIMARY KEY REFERENCES generation (id),
    state          BYTEA  NOT NULL CHECK (octet_length(state) % 128 = 0),
    results        BYTEA  NOT NULL CHECK (octet_length(results) % 96 = 0),
    requests       BYTEA  NOT NULL CHECK (octet_length(requests) % 40 = 0),
    receipt        TEXT   NOT NULL
);

-- A generation row may change only while PENDING, and then only its counters/status.
CREATE FUNCTION generation_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'generation % / % can never be deleted', OLD.namespace, OLD.name;
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'generation % / % is % and immutable', OLD.namespace, OLD.name, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.namespace <> OLD.namespace OR NEW.name <> OLD.name
       OR NEW.parent_id IS DISTINCT FROM OLD.parent_id OR NEW.fence <> OLD.fence
       OR NEW.policies_count <> OLD.policies_count OR NEW.seed_polin_sha256 <> OLD.seed_polin_sha256
       OR NEW.seed <> OLD.seed OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'generation % / % identity fields are immutable', OLD.namespace, OLD.name;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER generation_guard
    BEFORE UPDATE OR DELETE ON generation
    FOR EACH ROW EXECUTE FUNCTION generation_guard();

-- Child rows (entries, policy state, outputs) exist only under a PENDING generation. Once the
-- generation is PUBLISHED or DISCARDED no INSERT, UPDATE or DELETE is accepted for them.
CREATE FUNCTION pending_child_guard() RETURNS trigger AS $$
DECLARE
    gid BIGINT;
    st  TEXT;
BEGIN
    gid := CASE WHEN TG_OP = 'DELETE' THEN OLD.generation_id ELSE NEW.generation_id END;
    SELECT status INTO st FROM generation WHERE id = gid;
    IF st IS DISTINCT FROM 'PENDING' THEN
        RAISE EXCEPTION '% on % rejected: generation % is %', TG_OP, TG_TABLE_NAME, gid, COALESCE(st, 'missing');
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.generation_id <> OLD.generation_id THEN
        RAISE EXCEPTION 'rows cannot move between generations';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER generation_entry_guard
    BEFORE INSERT OR UPDATE OR DELETE ON generation_entry
    FOR EACH ROW EXECUTE FUNCTION pending_child_guard();

CREATE TRIGGER policy_state_guard
    BEFORE INSERT OR UPDATE OR DELETE ON policy_state
    FOR EACH ROW EXECUTE FUNCTION pending_child_guard();

CREATE TRIGGER generation_output_guard
    BEFORE INSERT OR UPDATE OR DELETE ON generation_output
    FOR EACH ROW EXECUTE FUNCTION pending_child_guard();

-- Entries are never rewritten even while pending: the ordinal sequence is append-only.
CREATE FUNCTION append_only_guard() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % rejected: entries are append-only', TG_OP, TG_TABLE_NAME;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER generation_entry_append_only
    BEFORE UPDATE OR DELETE ON generation_entry
    FOR EACH ROW EXECUTE FUNCTION append_only_guard();

CREATE TRIGGER generation_output_write_once
    BEFORE UPDATE OR DELETE ON generation_output
    FOR EACH ROW EXECUTE FUNCTION append_only_guard();
