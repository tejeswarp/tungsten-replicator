replicator.backup.agent.xtrabackup-full=com.continuent.tungsten.replicator.backup.generic.ScriptDumpAgent
replicator.backup.agent.xtrabackup-full.script=${replicator.home.dir}/samples/scripts/backup/xtrabackup.rb
replicator.backup.agent.xtrabackup-full.commandPrefix=@{REPL_BACKUP_COMMAND_PREFIX}
replicator.backup.agent.xtrabackup-full.hotBackupEnabled=true
replicator.backup.agent.xtrabackup-full.options=user=${replicator.global.db.user}&password=${replicator.global.db.password}&host=${replicator.global.db.host}&port=${replicator.global.db.port}&directory=@{REPL_MYSQL_XTRABACKUP_DIR}&tungsten_backups=@{SERVICE.REPL_BACKUP_STORAGE_DIR}&mysqllogdir=@{APPLIER.REPL_MASTER_LOGDIR}&mysqllogpattern=@{APPLIER.REPL_MASTER_LOGPATTERN}&mysqldatadir=@{APPLIER.REPL_MYSQL_DATADIR}&mysql_service_command=@{APPLIER.REPL_BOOT_SCRIPT}&my_cnf=@{APPLIER.REPL_MYSQL_CONF}