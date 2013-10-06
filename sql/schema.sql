drop table if exists job;

create table job ( job_id                  int           auto_increment primary key
                 , job_name                varchar(50)   not null
                 , job_desc                varchar(255)  not null
                 , job_execution_directory varchar(255)  not null
                 , job_command_line        varchar(255)  not null
                 , is_enabled              boolean       not null
                 , created_at              timestamp     not null
                 , created_by              varchar(50)   not null
                 , updated_at              timestamp     not null
                 , updated_by              varchar(50)   not null);


drop table if exists job_var;

create table job_var ( job_var      int         auto_increment primary key
                     , job_id       int         not null
                     , var_name     varchar(50) not null
                     , var_expr     varchar(50) not null
                     , created_at   timestamp   not null
                     , created_by   varchar(50) not null
                     , updated_at   timestamp   not null
                     , updated_by   varchar(50) not null);

alter table job_arg add constraint fk_job_arg_job_id foreign key(job_id) references job(job_id);


drop table if exists job_execution;

create table job_execution ( job_execution_id int       auto_increment primary key
                           , job_id           int       not null
                           , started_at       timestamp not null
                           , finished_at      timestamp null
                           , success          boolean   null);

alter table job_execution add constraint fk_job_execution_job_id foreign key (job_id) references job(job_id);


drop table if exists schedule;

create table schedule( schedule_id     int            auto_increment primary key
                     , schedule_name   varchar(50)    not null
                     , schedule_desc   varchar(255)   not null
                     , cron_expression varchar(50)    not null
                     , created_at      timestamp      not null
                     , created_by      varchar(50)    not null
                     , updated_at      timestamp      not null
                     , updated_by      varchar(50)    not null);

alter table schedule add constraint unq_schedule_schedule_name unique(schedule_name);

drop table if exists job_schedule;

create table job_schedule ( job_schedule_id int         auto_increment primary key
                          , job_id          int         not null
                          , schedule_id     int         not null
                          , created_at      timestamp   not null
                          , created_by      varchar(50) not null
                          , updated_at      timestamp   not null
                          , updated_by      varchar(50) not null);

alter table job_schedule add constraint fk_job_schedule_job_id foreign key (job_id) references job(job_id);
alter table job_schedule add constraint fk_job_schedule_schedule_id foreign key (schedule_id) references schedule(schedule_id);
alter table job_schedule add constraint unq_job_schedule_job_id_schedule_id unique(job_id,schedule_id);
