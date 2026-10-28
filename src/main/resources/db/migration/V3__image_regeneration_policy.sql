-- Expand-only: keep V1 and its baseline catalog immutable. Existing images receive one free regeneration.
-- Stop old image workers before deploying: old binaries cannot consume the new free allowance.
ALTER TABLE dream_images
    ADD COLUMN (free_regeneration_used BOOLEAN NOT NULL DEFAULT FALSE,
                free_regeneration_attempt BOOLEAN NOT NULL DEFAULT FALSE);

ALTER TABLE world_images
    ADD COLUMN (free_regeneration_used BOOLEAN NOT NULL DEFAULT FALSE,
                free_regeneration_attempt BOOLEAN NOT NULL DEFAULT FALSE);
