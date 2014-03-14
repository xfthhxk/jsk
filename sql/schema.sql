drop table if exists execution_edge;
drop table if exists execution_vertex;
drop table if exists execution_workflow;
drop table if exists execution;
drop table if exists execution_status;
drop table if exists node_schedule;
drop table if exists schedule;
drop table if exists node_alert;
drop table if exists alert;
drop table if exists job_var;
drop table if exists job;
drop table if exists workflow_edge;
drop table if exists workflow_vertex;
drop table if exists workflow;
drop table if exists node;
drop table if exists node_type;
drop table if exists node_directory;
drop table if exists agent;
drop table if exists app_user;


create table app_user ( app_user_id int         auto_increment primary key
                      , first_name  varchar(50) not null
                      , last_name   varchar(50) not null
                      , email       varchar(50) not null
                      , create_ts   timestamp   not null default current_timestamp()
                      , update_ts   timestamp   not null default current_timestamp());

alter table app_user add constraint unq_app_user_email unique(email);

insert into app_user (app_user_id, first_name, last_name, email)
              values (1, 'JSK_System', 'JSK_System', '')
                   , (2, 'Amar','Mehta','xfthhxk@gmail.com');


/* ---------------------------- Agent --------------------------- */
create table agent ( agent_id         int          auto_increment primary key
                   , agent_name       varchar(50)  not null
                   , creator_id       int          not null
                   , create_ts        timestamp    not null default current_timestamp()
                   , updater_id       int          not null 
                   , update_ts        timestamp    not null default current_timestamp());

alter table agent add constraint unq_agent_name unique(agent_name);
alter table agent add constraint fk_agent_creator_id foreign key (creator_id) references app_user(app_user_id);
alter table agent add constraint fk_agent_updater_id foreign key (updater_id) references app_user(app_user_id);

insert into agent (agent_id, agent_name, creator_id, updater_id) values (1, 'agent-1', 2, 2);


/* ---------------------------- Node Type  --------------------------- */

create table node_type ( node_type_id int auto_increment primary key
                       , node_type_name varchar(10) not null);

alter table node_type add constraint unq_node_type_name unique(node_type_name);

insert into node_type (node_type_id, node_type_name) values (1,'Job'), (2,'Workflow');

/* ---------------------------- Directory (Organization of jobs/workflows) ----------------------------------- */
/* parent_directory_id is null when there is no parent */
create table node_directory ( node_directory_id     int         auto_increment primary key
                            , node_directory_name   varchar(50) not null
                            , parent_directory_id   int         not null );

alter table node_directory add constraint unq_node_directory unique(node_directory_name, parent_directory_id);

insert into node_directory(node_directory_id, node_directory_name, parent_directory_id) values (1, '/', 0);


/* ---------------------------- Node ----------------------------------- */


create table node ( node_id                 int           auto_increment primary key
                  , node_name               varchar(50)   not null
                  , node_type_id            int           not null
                  , node_desc               varchar(255)  not null
                  , is_enabled              boolean       not null
                  , is_system               boolean       not null
                  , node_directory_id       int           not null
                  , create_ts               timestamp     not null default current_timestamp()
                  , creator_id              int           not null
                  , update_ts               timestamp     not null default current_timestamp()
                  , updater_id              int           not null);

alter table node add constraint unq_node_name unique(node_name);
alter table node add constraint fk_node_node_directory_id foreign key (node_directory_id) references node_directory(node_directory_id);
alter table node add constraint fk_node_node_type_id foreign key (node_type_id) references node_type(node_type_id);
alter table node add constraint fk_node_creator_id foreign key (creator_id) references app_user(app_user_id);
alter table node add constraint fk_node_updater_id foreign key (updater_id) references app_user(app_user_id);


/* ---------------------------- Job ----------------------------------- */
/* agent-id needs to be null to be able to setup jobs from explorer ui, ie quick create a job and then update its properties */

create table job ( job_id                  int           primary key
                 , execution_directory     varchar(255)  not null
                 , command_line            varchar(255)  not null
                 , agent_id                int           not null     
                 , max_concurrent          int           not null
                 , max_retries             int           not null);

alter table job add constraint fk_job_job_id foreign key (job_id) references node(node_id);
alter table job add constraint fk_job_agent_id foreign key (agent_id) references agent(agent_id);



/* ---------------------------- Job Var ----------------------------------- */
/* Overkill to store all the audit info on this table since it ties so closely to job */

create table job_var ( job_var_id       int         auto_increment primary key
                     , job_id           int         not null
                     , var_name         varchar(50) not null
                     , var_expr         varchar(50) not null );

alter table job_var add constraint fk_job_var_job_id foreign key (job_id) references node(node_id);


/* ---------------------------- Alert ----------------------------------- */
create table alert( alert_id        int            auto_increment primary key
                  , alert_name      varchar(50)    not null
                  , alert_desc      varchar(100)   not null
                  , recipients      varchar(255)   not null
                  , subject         varchar(100)   not null
                  , body            varchar(255)   not null
                  , is_for_error    boolean        not null
                  , create_ts       timestamp      not null default current_timestamp()
                  , creator_id      int            not null
                  , update_ts       timestamp      not null default current_timestamp()
                  , updater_id      int            not null);

alter table alert add constraint unq_alert_alert_name unique(alert_name);
alter table alert add constraint fk_alert_creator_id foreign key (creator_id) references app_user(app_user_id);
alter table alert add constraint fk_alert_updater_id foreign key (updater_id) references app_user(app_user_id);

/* ---------------------------- Node Alert ----------------------------------- */
/* Doesn't make sense to have update info here since you either create or delete */
create table node_alert( node_alert_id  int       auto_increment primary key
                       , node_id        int       not null
                       , alert_id       int       not null
                       , create_ts      timestamp not null default current_timestamp()
                       , creator_id     int       not null);

alter table node_alert add constraint fk_node_alert_node_id foreign key (node_id) references node(node_id);
alter table node_alert add constraint fk_node_alert_alert_id foreign key (alert_id) references alert(alert_id);
alter table node_alert add constraint fk_node_alert_creator_id foreign key (creator_id) references app_user(app_user_id);
alter table node_alert add constraint unq_node_alert_node_id_alert_id unique(node_id, alert_id);


/* ---------------------------- Schedule ----------------------------------- */
create table schedule( schedule_id     int            auto_increment primary key
                     , schedule_name   varchar(50)    not null
                     , schedule_desc   varchar(255)   not null
                     , cron_expression varchar(50)    not null
                     , create_ts       timestamp      not null default current_timestamp()
                     , creator_id      int            not null
                     , update_ts       timestamp      not null default current_timestamp()
                     , updater_id      int            not null);

alter table schedule add constraint unq_schedule_schedule_name unique(schedule_name);
alter table schedule add constraint fk_schedule_creator_id foreign key (creator_id) references app_user(app_user_id);
alter table schedule add constraint fk_schedule_updater_id foreign key (updater_id) references app_user(app_user_id);


/* ---------------------------- Node Schedule ----------------------------------- */
/* Doesn't make sense to have update info here since you either create or delete */
create table node_schedule ( node_schedule_id int         auto_increment primary key
                           , node_id          int         not null
                           , schedule_id      int         not null
                           , create_ts        timestamp   not null default current_timestamp()
                           , creator_id       int         not null);

alter table node_schedule add constraint fk_node_schedule_node_id foreign key (node_id) references node(node_id);
alter table node_schedule add constraint fk_node_schedule_schedule_id foreign key (schedule_id) references schedule(schedule_id);
alter table node_schedule add constraint fk_node_schedule_creator_id foreign key (creator_id) references app_user(app_user_id);
alter table node_schedule add constraint unq_node_schedule_node_id_schedule_id unique(node_id, schedule_id);


/* ---------------------------- Workflow ----------------------------------- */

create table workflow ( workflow_id int primary key);

alter table workflow add constraint fk_workflow_workflow_id foreign key (workflow_id) references node(node_id);

/* --------------------------------------------------------------------------------------
   Jobs that are not part of a workflow need a workflow id to save to executions table.
   -------------------------------------------------------------------------------------- */

insert into node(node_id, node_name, node_type_id, node_desc, is_enabled, is_system, node_directory_id, creator_id, updater_id)
          values(1, '_JSK_Synthetic_Workflow_', 2, 'Synthetic workflow', true, true, 1, 1, 1);

insert into workflow(workflow_id) values (1);


create table workflow_vertex ( workflow_vertex_id  int          auto_increment primary key
                             , workflow_id         int          not null
                             , node_id             int          not null
                             , layout              varchar(255) not null);

alter table workflow_vertex add constraint fk_workflow_vertex_workflow_id foreign key (workflow_id) references workflow(workflow_id);
alter table workflow_vertex add constraint fk_workflow_vertex_node_id foreign key (node_id) references node(node_id);


create table workflow_edge ( workflow_edge_id int auto_increment primary key
                           , vertex_id        int not null
                           , next_vertex_id   int not null
                           , success          boolean not null);

alter table workflow_edge add constraint fk_workflow_edge_vertex_id foreign key (vertex_id) references workflow_vertex(workflow_vertex_id);
alter table workflow_edge add constraint fk_workflow_edge_next_vertex_id foreign key (next_vertex_id) references workflow_vertex(workflow_vertex_id);
alter table workflow_edge add constraint unq_workflow_edge unique(vertex_id, next_vertex_id, success);



/* ---------------------------- Execution Status -----------------------------------
     The unknown status occurs when networks/agents go down and we can't communicate
     with the agent that was responsible for the job.
   -------------------------------------------------------------------------------------- */
create table execution_status (  execution_status_id int         auto_increment primary key
                               , status_code         varchar(20)
                               , status_desc         varchar(50));

alter table execution_status add constraint unq_execution_status_code unique(status_code);

insert into execution_status (execution_status_id, status_code, status_desc)
                      values (1, 'unexecuted', 'Unexecuted')
                           , (2, 'started', 'Started')
                           , (3, 'finished-success', 'Finished successfully')
                           , (4, 'finished-errored', 'Finished with errors')
                           , (5, 'aborted', 'Aborted')
                           , (6, 'unknown', 'Unknown')
                           , (7, 'pending', 'Pending')
                           , (8, 'forced-success', 'Forced Success');


/* ---------------------------- Execution ----------------------------------- */
create table execution ( execution_id        int       auto_increment primary key
                       , status_id           int       not null
                       , start_ts          timestamp not null default current_timestamp()
                       , finish_ts         timestamp );


create table execution_workflow ( execution_workflow_id   int       auto_increment primary key
                                , execution_id            int       not null
                                , workflow_id             int       not null
                                , root                    boolean   not null
                                , status_id               int       not null
                                , start_ts                timestamp
                                , finish_ts               timestamp );

alter table execution_workflow add constraint fk_execution_workflow_workflow_id foreign key (workflow_id) references workflow(workflow_id);
alter table execution_workflow add constraint fk_execution_workflow_status_id foreign key (status_id) references execution_status(execution_status_id);
alter table execution_workflow add constraint fk_execution_workflow_execution_id foreign key (execution_id) references execution(execution_id);


/* --- This doesn't handle some sort of repeated invocation type thing.
       Need execution_vertex_invocation(execution_vertex_invocation_id
                                      , execution_vertex_id
                                      , status_id
                                      , start_ts
                                      , finish_ts)

  agent_id is nullable because workflow vertices don't have an agent_id (they're not actually run)                                      

-----*/

create table execution_vertex ( execution_vertex_id        int       auto_increment primary key
                              , execution_id               int       not null
                              , execution_workflow_id      int       not null
                              , node_id                    int       not null
                              , runs_execution_workflow_id int       null
                              , status_id                  int       not null
                              , start_ts                   timestamp
                              , finish_ts                  timestamp
                              , layout                     varchar(255));

alter table execution_vertex add constraint fk_execution_vertex_execution_workflow_id
  foreign key (execution_workflow_id) references execution_workflow(execution_workflow_id);

alter table execution_vertex add constraint fk_execution_vertex_runs_execution_workflow_id
  foreign key (runs_execution_workflow_id) references execution_workflow(execution_workflow_id);

alter table execution_vertex add constraint fk_execution_vertex_execution_id foreign key (execution_id) references execution(execution_id);
alter table execution_vertex add constraint fk_execution_vertex_node_id foreign key (node_id) references node(node_id);
alter table execution_vertex add constraint fk_execution_vertex_status_id foreign key (status_id) references execution_status(execution_status_id);
alter table execution_vertex add constraint unq_execution_vertex unique(execution_workflow_id, node_id);


create table execution_edge ( execution_edge_id int     auto_increment primary key
                            , execution_id      int     not null
                            , vertex_id         int     not null
                            , next_vertex_id    int     not null
                            , success           boolean not null );


alter table execution_edge add constraint fk_execution_edge_vertex_id foreign key (vertex_id) references execution_vertex(execution_vertex_id);
alter table execution_edge add constraint fk_execution_edge_next_vertex_id foreign key (next_vertex_id) references execution_vertex(execution_vertex_id);
alter table execution_edge add constraint unq_execution_edge unique(execution_id, vertex_id, next_vertex_id, success);





