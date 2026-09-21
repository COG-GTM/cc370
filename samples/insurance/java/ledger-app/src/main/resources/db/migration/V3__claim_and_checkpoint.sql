-- Writer takeover and verified-prefix checkpoints.
--
-- contract_identity: content identity (class bytes + rate table) of the contract that created the
--   generation; pinned, immutable. A claim under a different identity is refused.
-- checkpoint: hash chain over the committed entries (seed chain when nothing is committed); written
--   in the same transaction as every entry, so the row and the entry stream are bound together.
-- claims: how many times the generation has been claimed by a replacement writer.
-- generation_entry.chain: the chain value after this entry.
-- generation_claim: append-only record of every ownership transition.
ALTER TABLE generation ADD COLUMN contract_identity TEXT;
ALTER TABLE generation ADD COLUMN checkpoint        TEXT;
ALTER TABLE generation ADD COLUMN claims            BIGINT NOT NULL DEFAULT 0;
ALTER TABLE generation_entry ADD COLUMN chain       TEXT;

CREATE TABLE generation_claim (
    generation_id         BIGINT      NOT NULL REFERENCES generation (id),
    claim_no              BIGINT      NOT NULL CHECK (claim_no >= 1),
    old_fence             BIGINT      NOT NULL,
    new_fence             BIGINT      NOT NULL CHECK (new_fence > old_fence),
    old_writer_id         TEXT,
    new_writer_id         TEXT        NOT NULL,
    old_lease_expires_at  TIMESTAMPTZ,
    verified_last_ordinal BIGINT      NOT NULL CHECK (verified_last_ordinal >= 0),
    checkpoint            TEXT        NOT NULL,
    claimed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (generation_id, claim_no)
);

CREATE TRIGGER generation_claim_append_only
    BEFORE UPDATE OR DELETE ON generation_claim
    FOR EACH ROW EXECUTE FUNCTION append_only_guard();

-- Ownership may move (claim), but only forward: the fence strictly increases, the writer changes
-- only together with the fence, the ordinal counter advances by at most one per statement and
-- never goes back, the claim counter never goes back, and the pinned identity fields stay fixed.
CREATE OR REPLACE FUNCTION generation_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'generation % / % can never be deleted', OLD.namespace, OLD.name;
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'generation % / % is % and immutable', OLD.namespace, OLD.name, OLD.status;
    END IF;
    IF NEW.id <> OLD.id OR NEW.namespace <> OLD.namespace OR NEW.name <> OLD.name
       OR NEW.parent_id IS DISTINCT FROM OLD.parent_id
       OR NEW.policies_count <> OLD.policies_count OR NEW.seed_polin_sha256 <> OLD.seed_polin_sha256
       OR NEW.seed <> OLD.seed OR NEW.created_at <> OLD.created_at
       OR NEW.manifest IS DISTINCT FROM OLD.manifest
       OR NEW.manifest_sha256 IS DISTINCT FROM OLD.manifest_sha256
       OR NEW.contract_identity IS DISTINCT FROM OLD.contract_identity THEN
        RAISE EXCEPTION 'generation % / % identity fields are immutable', OLD.namespace, OLD.name;
    END IF;
    IF NEW.fence < OLD.fence THEN
        RAISE EXCEPTION 'generation % / % fence can only increase', OLD.namespace, OLD.name;
    END IF;
    IF NEW.fence = OLD.fence AND NEW.writer_id IS DISTINCT FROM OLD.writer_id THEN
        RAISE EXCEPTION 'generation % / % writer changes only with a new fence', OLD.namespace, OLD.name;
    END IF;
    IF NEW.last_ordinal < OLD.last_ordinal OR NEW.last_ordinal > OLD.last_ordinal + 1 THEN
        RAISE EXCEPTION 'generation % / % ordinals are contiguous and never rewound', OLD.namespace, OLD.name;
    END IF;
    IF NEW.claims < OLD.claims THEN
        RAISE EXCEPTION 'generation % / % claim counter never goes back', OLD.namespace, OLD.name;
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;
