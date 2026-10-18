-- Runs automatically only for a new mysql_data volume.
-- Existing development volumes: execute this file once as MySQL root.
CREATE TABLE IF NOT EXISTS `databridge_source`.`sync_probe` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `marker` CHAR(36) NOT NULL UNIQUE
);
GRANT INSERT ON `databridge_source`.`sync_probe` TO 'databridge'@'%';
