drop index ACT_RE_DECISION_DEF.ACT_IDX_DEC_DEF_TENANT_ID;
drop index ACT_RE_DECISION_DEF.ACT_IDX_DEC_DEF_REQ_ID;
drop index ACT_RE_DECISION_REQ_DEF.ACT_IDX_DEC_REQ_DEF_TENANT_ID;

alter table ACT_RE_DECISION_DEF 
    drop constraint ACT_FK_DEC_REQ;

if exists (select TABLE_NAME from INFORMATION_SCHEMA.TABLES where TABLE_NAME = 'ACT_RE_DECISION_DEF') drop table ACT_RE_DECISION_DEF;
if exists (select TABLE_NAME from INFORMATION_SCHEMA.TABLES where TABLE_NAME = 'ACT_RE_DECISION_REQ_DEF') drop table ACT_RE_DECISION_REQ_DEF;