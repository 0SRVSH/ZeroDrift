-- Evaluates net sum of debits and credits for the affected journal entry
CREATE OR REPLACE FUNCTION verify_journal_entry_parity()
RETURNS TRIGGER AS $$
DECLARE
    net_discrepancy NUMERIC(18, 4);
BEGIN
    SELECT COALESCE(
        SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END),
        0
    )
    INTO net_discrepancy
    FROM postings
    WHERE journal_entry_id = NEW.journal_entry_id;

    IF net_discrepancy <> 0 THEN
        RAISE EXCEPTION 'Double-entry parity violation: Journal % does not balance. Net discrepancy: %',
            NEW.journal_entry_id, net_discrepancy;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Constraint trigger deferred to transaction commit time
CREATE CONSTRAINT TRIGGER trg_postings_verify_parity
AFTER INSERT OR UPDATE ON postings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION verify_journal_entry_parity();
