drop table if exists app_user;

create table app_user ( app_user_id int         auto_increment primary key
                      , first_name  varchar(50) not null
                      , last_name   varchar(50) not null
                      , email       varchar(50) not null
                      , created_at  timestamp   not null default current_timestamp()
                      , updated_at  timestamp   not null default current_timestamp());

alter table app_user add constraint unq_app_user_email unique(email);

insert into app_user (first_name, last_name, email) values ('Amar','Mehta','xfthhxk@gmail.com');


drop table if exists job;

create table job ( job_id                  int           auto_increment primary key
                 , job_name                varchar(50)   not null
                 , job_desc                varchar(255)  not null
                 , execution_directory     varchar(255)  not null
                 , command_line            varchar(255)  not null
                 , is_enabled              boolean       not null
                 , created_at              timestamp     not null default current_timestamp()
                 , create_user_id          int           not null
                 , updated_at              timestamp     not null default current_timestamp()
                 , update_user_id          int           not null);

alter table job add constraint fk_job_create_user_id foreign key (create_user_id) references app_user(app_user_id);
alter table job add constraint fk_job_update_user_id foreign key (update_user_id) references app_user(app_user_id);


drop table if exists job_var;

/* Overkill to store all the audit info on this table since it ties so closely to job */

create table job_var ( job_var_id       int         auto_increment primary key
                     , job_id           int         not null
                     , var_name         varchar(50) not null
                     , var_expr         varchar(50) not null );


drop table if exists job_execution_status;
create table job_execution_status ( job_execution_status_id int auto_increment primary key
                                  , status_code varchar(20)
                                  , status_desc varchar(255));

alter table job_execution_status add constraint unq_job_execution_status unique(status_code);
insert into job_execution_status (job_execution_status_id, status_code, status_desc)
       values (1, 'started', 'Job started.')
            , (2, 'finished-success', 'Finished successfully.')
            , (3, 'finished-errored', 'Finished with errors.');


drop table if exists job_execution;

create table job_execution ( job_execution_id    int       auto_increment primary key
                           , job_id              int       not null
                           , execution_status_id int       not null
                           , started_at          timestamp not null default current_timestamp()
                           , finished_at         timestamp null );

alter table job_execution add constraint fk_job_execution_job_id foreign key (job_id) references job(job_id);
alter table job_execution add constraint fk_job_execution_job_status_id foreign key (execution_status_id) references job_execution_status(job_execution_status_id);

drop table if exists schedule;

create table schedule( schedule_id     int            auto_increment primary key
                     , schedule_name   varchar(50)    not null
                     , schedule_desc   varchar(255)   not null
                     , cron_expression varchar(50)    not null
                     , created_at      timestamp      not null default current_timestamp()
                     , create_user_id  int            not null
                     , updated_at      timestamp      not null default current_timestamp()
                     , update_user_id  varchar(50)    not null);

alter table schedule add constraint unq_schedule_schedule_name unique(schedule_name);
alter table schedule add constraint fk_schedule_create_user_id foreign key (create_user_id) references app_user(app_user_id);
alter table schedule add constraint fk_schedule_update_user_id foreign key (update_user_id) references app_user(app_user_id);

drop table if exists job_schedule;

/* Doesn't make sense to have update info here since you either create or delete */
create table job_schedule ( job_schedule_id int         auto_increment primary key
                          , job_id          int         not null
                          , schedule_id     int         not null
                          , created_at      timestamp   not null default current_timestamp()
                          , create_user_id  varchar(50) not null);

alter table job_schedule add constraint fk_job_schedule_job_id foreign key (job_id) references job(job_id);
alter table job_schedule add constraint fk_job_schedule_schedule_id foreign key (schedule_id) references schedule(schedule_id);
alter table job_schedule add constraint fk_job_schedule_create_user_id foreign key (create_user_id) references app_user(app_user_id);
alter table job_schedule add constraint unq_job_schedule_job_id_schedule_id unique(job_id, schedule_id);


drop table if exists email_profile;

create table email_profile ( email_profile_id     int auto_increment primary key
                           , email_profile_name   varchar(50) not null
                           , host                 varchar(50) not null
                           , port                 int         not null
                           , email_user_id        varchar(50) not null
                           , email_pass           varchar(50) not null);

alter table email_profile add constraint unq_email_profile_name unique(email_profile_name);

insert into email_profile (email_profile_name, host, port, email_user_id, email_pass)
       values ('Google', 'smtp.gmail.com', 587, 'fix-me-user', 'fix-me-pass');


