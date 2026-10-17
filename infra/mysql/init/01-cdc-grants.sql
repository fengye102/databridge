-- The official MySQL entrypoint creates databridge from MYSQL_USER first.
-- Automatically runs only on an empty mysql_data volume; reapply explicitly to an existing volume.
-- The image initially grants ALL on MYSQL_DATABASE; narrow the CDC account.
REVOKE ALL ON `databridge\_source`.* FROM 'databridge'@'%';
GRANT SELECT ON `databridge\_source`.* TO 'databridge'@'%';
GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
ON *.* TO 'databridge'@'%';
