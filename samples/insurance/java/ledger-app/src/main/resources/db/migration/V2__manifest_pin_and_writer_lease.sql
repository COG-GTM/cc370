-- The expected manifest is pinned to the generation row when the generation is created (import or
-- begin) and is immutable afterwards; publication validates against it and never accepts one.
ALTER TABLE generation ADD COLUMN manifest        TEXT;
ALTER TABLE generation ADD COLUMN manifest_sha256 TEXT;

-- One live writer per pending generation, identified by the store instance that begun it, with a
-- lease that the owning instance renews while it runs. Startup recovery of another instance treats
-- a PENDING row whose lease is still valid as a live writer and leaves it alone; only an expired
-- lease is orphaned and discarded (fail closed: the pending generation is never resumed).
ALTER TABLE generation ADD COLUMN writer_id        TEXT;
ALTER TABLE generation ADD COLUMN lease_expires_at TIMESTAMPTZ;
ALTER TABLE generation ADD COLUMN discard_reason   TEXT;

CREATE OR REPLACE FUNCTION generation_guard() RETURNS trigger AS $$
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
       OR NEW.seed <> OLD.seed OR NEW.created_at <> OLD.created_at
       OR NEW.manifest IS DISTINCT FROM OLD.manifest
       OR NEW.manifest_sha256 IS DISTINCT FROM OLD.manifest_sha256
       OR NEW.writer_id IS DISTINCT FROM OLD.writer_id THEN
        RAISE EXCEPTION 'generation % / % identity fields are immutable', OLD.namespace, OLD.name;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

-- The child guard reads the generation row with FOR SHARE. Publication and every fenced commit
-- hold the row FOR UPDATE / via UPDATE, which conflicts with FOR SHARE, so a direct child-row write
-- that begins while the generation is PENDING either commits before the publication transaction
-- can take the row lock (and publication then rejects the integrity break), or waits for the
-- publication to commit and is then rejected because the status is no longer PENDING. The
-- status check and the write can no longer be separated by a concurrent publication.
CREATE OR REPLACE FUNCTION pending_child_guard() RETURNS trigger AS $$
DECLARE
    gid BIGINT;
    st  TEXT;
BEGIN
    gid := CASE WHEN TG_OP = 'DELETE' THEN OLD.generation_id ELSE NEW.generation_id END;
    SELECT status INTO st FROM generation WHERE id = gid FOR SHARE;
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
