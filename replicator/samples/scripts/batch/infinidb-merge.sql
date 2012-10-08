# Merge script for InfiniDB.
#
# Delete rows.  This query applies all deletes that match, need it or not.
DELETE FROM %%BASE_TABLE%%
  WHERE %%BASE_TABLE%%.%%PKEY%% IN (SELECT %%PKEY%% FROM %%STAGE_TABLE_FQN%% WHERE tungsten_opcode = 'D')

# Insert rows.  This query loads each inserted row provided that the
# insert is (a) the last insert processed and (b) is not followed by a
# delete.  The subquery could probably be optimized to a join.
INSERT INTO %%BASE_TABLE%%(%%BASE_COLUMNS%%)
  SELECT %%BASE_COLUMNS%% FROM %%STAGE_TABLE_FQN%% AS stage_a
  WHERE tungsten_opcode='I' AND tungsten_row_id IN
  (SELECT MAX(tungsten_row_id) FROM %%STAGE_TABLE_FQN%% GROUP BY %%PKEY%%)