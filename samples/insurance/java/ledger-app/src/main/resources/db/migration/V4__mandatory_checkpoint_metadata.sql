-- The checkpoint metadata introduced by V3 is mandatory: a generation without its pinned contract
-- identity or checkpoint, or an entry without its chain value, cannot be verified and must not
-- exist. The store refuses such rows as well (fail closed, no downgrade to weaker verification);
-- the schema makes the missing state unrepresentable in the first place.
ALTER TABLE generation ALTER COLUMN contract_identity SET NOT NULL;
ALTER TABLE generation ALTER COLUMN checkpoint        SET NOT NULL;
ALTER TABLE generation_entry ALTER COLUMN chain       SET NOT NULL;
