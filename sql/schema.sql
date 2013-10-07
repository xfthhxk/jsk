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


drop table if exists job_start;

create table job_start ( job_start_id int       auto_increment primary key
                       , job_id       int       not null
                       , started_at   timestamp not null default current_timestamp());

alter table job_start add constraint fk_job_start_job_id foreign key (job_id) references job(job_id);

drop table if exists job_finish;

create table job_finish ( job_start_id int       not null
                        , finished_at  timestamp not null default current_timestamp());

alter table job_finish add constraint fk_job_finish_job_start_id foreign key (job_start_id) references job_start(job_start_id);


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
