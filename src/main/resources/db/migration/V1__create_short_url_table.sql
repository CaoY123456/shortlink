-- The mapping table that backs the whole service.
--
-- Why `short_code` is nullable: we insert the row first to obtain the
-- auto-increment id, encode that id into a short code, then write the code
-- back. Between those two statements the column is briefly NULL.
-- MySQL allows multiple NULLs in a UNIQUE index, so this is legal.
--
-- Why AUTO_INCREMENT starts at 1000000000: Base62 of a small id would produce
-- a 1-2 character code ("1", "b"). Starting past 62^5 guarantees every code is
-- 6 characters from day one.
create table short_url
(
    id          bigint        not null auto_increment,
    short_code  varchar(16)   null comment 'Base62 encoded id, unique',
    long_url    varchar(2048) not null,
    click_count bigint        not null default 0,
    created_at  datetime      not null default current_timestamp,
    primary key (id),
    unique key uk_short_code (short_code)
) engine = innodb
  auto_increment = 1000000000
  default charset = utf8mb4
  collate = utf8mb4_unicode_ci comment 'long url <-> short code mapping';
