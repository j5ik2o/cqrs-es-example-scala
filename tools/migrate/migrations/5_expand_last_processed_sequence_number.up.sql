-- ESA core-contract T-9: a sequence number is published as a JSON number, so it must stay within the JavaScript safe
-- integer range (2^53 - 1). An INT column tops out at 2147483647, so widen every read model column that stores the last
-- applied sequence number to BIGINT.
--
-- The existing migrations 0-3 are intentionally left unchanged; this migration upgrades databases that were created
-- from them.
ALTER TABLE `read_model_positions`
    MODIFY COLUMN `last_processed_sequence_number` BIGINT NOT NULL;

ALTER TABLE `group_chats`
    MODIFY COLUMN `last_processed_sequence_number` BIGINT NOT NULL;

ALTER TABLE `members`
    MODIFY COLUMN `last_processed_sequence_number` BIGINT NOT NULL;

ALTER TABLE `messages`
    MODIFY COLUMN `last_processed_sequence_number` BIGINT NOT NULL;
