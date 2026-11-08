-- WARNING: this down migration is lossy and must not be used after the read model has stored a sequence number above
-- 2147483647 (INT SIGNED max).
--
-- MySQL 8 runs in strict mode by default, so an ALTER that narrows a column holding an out-of-range value fails with
-- "Out of range value for column 'last_processed_sequence_number'" (error 1264; some versions report "Data truncated").
-- MySQL DDL is not transactional, so if a later table fails, the tables altered before it are already narrowed and the
-- migration is left partially applied and dirty. That is why every table's maximum must be checked first.
--
-- If strict mode is disabled, the values are instead clamped to 2147483647, silently corrupting the stored position: a
-- later projection then sees a position behind the journal and may apply or reject events incorrectly.
--
-- Before running this, stop the Read Model Updater and every other read model writer, then confirm that no stored
-- value exceeds 2147483647:
--   SELECT MAX(last_processed_sequence_number) FROM read_model_positions;
--   SELECT MAX(last_processed_sequence_number) FROM group_chats;
--   SELECT MAX(last_processed_sequence_number) FROM members;
--   SELECT MAX(last_processed_sequence_number) FROM messages;
--
-- A stored maximum inside the INT range is not enough on its own. If the Read Model Updater is behind and the latest
-- head or journal seq_nr for an aggregate is already above 2147483647, that value has not been projected yet, so the
-- SELECTs above still pass. After the down migration the projection would fail against the narrowed column. Compare
-- the latest head and journal seq_nr with read_model_positions.last_processed_sequence_number for every aggregate and
-- treat any pending value above 2147483647 as a blocker.
ALTER TABLE `read_model_positions`
    MODIFY COLUMN `last_processed_sequence_number` INT NOT NULL;

ALTER TABLE `group_chats`
    MODIFY COLUMN `last_processed_sequence_number` INT NOT NULL;

ALTER TABLE `members`
    MODIFY COLUMN `last_processed_sequence_number` INT NOT NULL;

ALTER TABLE `messages`
    MODIFY COLUMN `last_processed_sequence_number` INT NOT NULL;
