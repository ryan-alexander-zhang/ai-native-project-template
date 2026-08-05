-- The transition migration: the version column the library's write path needs.
--
-- `DEFAULT 0` and NOT NULL, so the millions of existing rows are valid immediately and no backfill is
-- needed. That part is genuinely easy, and it is the part everybody focuses on.
--
-- `DEFAULT 1` — and NOT zero, which is the detail that costs an afternoon if you get it wrong.
--
-- The library reads `version == 0` as "this aggregate has never been persisted" and takes the INSERT branch.
-- A legacy table with `DEFAULT 0` therefore gives every one of its existing rows the value that means
-- "unsaved", and the first write to any pre-migration row is an INSERT of a row that already exists:
--
--     DuplicateEntityException: aggregate Refund[10] already exists. Either two concurrent creates raced on
--     the same identity ... or this aggregate was reconstituted by a factory that forgot to call
--     restoreVersion(...)
--
-- Which is a helpful message pointing at the wrong cause: nothing forgot to restore the version, the column
-- default handed it a zero. Measured in AutoIncrementIdentityTest; noted in docs/issue/issue-00171.
--
-- What the column does NOT do — the other finding, in VersionColumnTest — is protect anything while a second
-- writer still exists. The library's update says `WHERE version = <loaded>`; a legacy `UPDATE` that never
-- mentions the column leaves it exactly where it was, so the check passes and the legacy change is
-- overwritten. Silently. Adding the column is necessary and is not sufficient.
ALTER TABLE legacy_refunds ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

-- The other half of the coexistence question. The new context wants an identity it minted; the table has
-- BIGSERIAL. Rather than choose, carry both for the duration:
--   * `id` stays the identity for everything that already references it (the FK, the legacy code, any
--     report anybody wrote in the last decade);
--   * `public_id` is the identity the new context hands outward, so no external contract is ever minted
--     against a number that means "insertion order in one database".
-- Nullable, because the existing rows do not have one, and backfilled below for the ones that exist.
ALTER TABLE legacy_refunds ADD COLUMN public_id UUID;
UPDATE legacy_refunds SET public_id = gen_random_uuid() WHERE public_id IS NULL;
CREATE UNIQUE INDEX uq_legacy_refunds_public_id ON legacy_refunds (public_id);
