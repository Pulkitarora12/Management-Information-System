-- Baseline migration: captures the schema as it exists in production as of 2026-11-06.
-- Generated from `mysqldump --no-data mis` and reconciled with the JPA entities.
-- This script is NOT executed against the existing database (Flyway baselines it),
-- but it IS executed against any fresh database (new dev machine, Testcontainers, mis_bench).

SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `roles` (
  `role_id` int NOT NULL AUTO_INCREMENT,
  `role_name` enum('ROLE_ADMIN','ROLE_USER') DEFAULT NULL,
  PRIMARY KEY (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `users` (
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `account_expiry_date` date DEFAULT NULL,
  `account_non_expired` bit(1) NOT NULL,
  `account_non_locked` bit(1) NOT NULL,
  `created_date` datetime(6) DEFAULT NULL,
  `credentials_expiry_date` date DEFAULT NULL,
  `credentials_non_expired` bit(1) NOT NULL,
  `email` varchar(50) NOT NULL,
  `employee_department` varchar(255) DEFAULT NULL,
  `employee_full_name` varchar(255) DEFAULT NULL,
  `employee_phone` varchar(255) DEFAULT NULL,
  `enabled` bit(1) NOT NULL,
  `is_two_factor_enabled` bit(1) NOT NULL,
  `password` varchar(120) DEFAULT NULL,
  `sign_up_method` varchar(255) DEFAULT NULL,
  `two_factor_secret` varchar(255) DEFAULT NULL,
  `updated_date` datetime(6) DEFAULT NULL,
  `username` varchar(20) NOT NULL,
  `verified` bit(1) NOT NULL,
  `role_id` int DEFAULT NULL,
  `performance_points` int NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `UKr43af9ap4edm43mmtq01oddj6` (`username`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`),
  KEY `FKp56c1712k691lhsyewcssf40f` (`role_id`),
  CONSTRAINT `FKp56c1712k691lhsyewcssf40f` FOREIGN KEY (`role_id`) REFERENCES `roles` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `verification_token` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `expiry_date` datetime(6) DEFAULT NULL,
  `token` varchar(255) NOT NULL,
  `used` bit(1) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKp678btf3r9yu6u8aevyb4ff0m` (`token`),
  KEY `FK3asw9wnv76uxu3kr1ekq4i1ld` (`user_id`),
  CONSTRAINT `FK3asw9wnv76uxu3kr1ekq4i1ld` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `employees` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(500) NOT NULL,
  `bank_account` varchar(255) DEFAULT NULL,
  `cheque_path` varchar(255) DEFAULT NULL,
  `created_date` datetime(6) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `esi_number` varchar(255) DEFAULT NULL,
  `gender` varchar(255) NOT NULL,
  `name` varchar(255) NOT NULL,
  `phone_number` varchar(255) NOT NULL,
  `photo_path` varchar(255) DEFAULT NULL,
  `psi_number` varchar(255) DEFAULT NULL,
  `roles` varchar(255) DEFAULT NULL,
  `salary` double DEFAULT NULL,
  `total_time_given` double DEFAULT NULL,
  `total_time_left` double DEFAULT NULL,
  `updated_date` datetime(6) DEFAULT NULL,
  `contractor_name` varchar(255) DEFAULT NULL,
  `employee_code` varchar(255) DEFAULT NULL,
  `father_name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKetqhw9qqnad1kyjq3ks1glw8x` (`employee_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `in_out_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `duration_minutes` bigint DEFAULT NULL,
  `in_time` datetime(6) DEFAULT NULL,
  `out_time` datetime(6) NOT NULL,
  `remarks` varchar(255) DEFAULT NULL,
  `employee_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK5etjujvvnkkxx1rwgkk1b4a2d` (`employee_id`),
  CONSTRAINT `FK5etjujvvnkkxx1rwgkk1b4a2d` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `master` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `master_field` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `data_type` enum('BOOLEAN','INTEGER','STRING') DEFAULT NULL,
  `field_name` varchar(255) DEFAULT NULL,
  `master_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKs66o04uecq8chrqujnxsibyud` (`master_id`),
  CONSTRAINT `FKs66o04uecq8chrqujnxsibyud` FOREIGN KEY (`master_id`) REFERENCES `master` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `field_data` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `value` varchar(255) DEFAULT NULL,
  `field_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK8j82yjuufgd1v29d2orbeff10` (`field_id`),
  CONSTRAINT `FK8j82yjuufgd1v29d2orbeff10` FOREIGN KEY (`field_id`) REFERENCES `master_field` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `note` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content` longtext,
  `owner_name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `production_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `date` date DEFAULT NULL,
  `inspector` varchar(255) DEFAULT NULL,
  `line` varchar(255) DEFAULT NULL,
  `machine` varchar(255) DEFAULT NULL,
  `operation` varchar(255) DEFAULT NULL,
  `operator1` varchar(255) DEFAULT NULL,
  `operator2` varchar(255) DEFAULT NULL,
  `part_name` varchar(255) DEFAULT NULL,
  `part_no` varchar(255) DEFAULT NULL,
  `remarks` varchar(255) DEFAULT NULL,
  `sheet_size` varchar(255) DEFAULT NULL,
  `shift` varchar(255) DEFAULT NULL,
  `synced_to_sheets` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `production_downtime` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `minutes` int DEFAULT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `production_entry_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKkr82i9elu7mt6250vmyxrbwlj` (`production_entry_id`),
  CONSTRAINT `FKkr82i9elu7mt6250vmyxrbwlj` FOREIGN KEY (`production_entry_id`) REFERENCES `production_entries` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `production_timeslots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `from_time` time(6) DEFAULT NULL,
  `produced` int DEFAULT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `rejected` int DEFAULT NULL,
  `remarks` varchar(255) DEFAULT NULL,
  `segregated` int DEFAULT NULL,
  `to_time` time(6) DEFAULT NULL,
  `production_entry_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKn3y2se244s9g1sc36asnrebhk` (`production_entry_id`),
  CONSTRAINT `FKf24duu97kqd6tdbk45f79qhy8` FOREIGN KEY (`production_entry_id`) REFERENCES `production_entries` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `rmir_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bundlegw` double DEFAULT NULL,
  `bundle_grade` varchar(255) DEFAULT NULL,
  `bundlenw` double DEFAULT NULL,
  `bundle_no` varchar(255) DEFAULT NULL,
  `bundle_size` varchar(255) DEFAULT NULL,
  `grade` varchar(255) DEFAULT NULL,
  `inspector` varchar(255) DEFAULT NULL,
  `part_name` varchar(255) DEFAULT NULL,
  `part_no` varchar(255) DEFAULT NULL,
  `remarks` varchar(255) DEFAULT NULL,
  `rm_size` varchar(255) DEFAULT NULL,
  `stdgw` double DEFAULT NULL,
  `supplier` varchar(255) DEFAULT NULL,
  `created_time` time(6) NOT NULL,
  `created_date` date DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `rmir_observations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `b` double DEFAULT NULL,
  `gw_sheet` double DEFAULT NULL,
  `h` double DEFAULT NULL,
  `l` double DEFAULT NULL,
  `rmir_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKiiybq7a5k50ti6opsjo8yawb` (`rmir_id`),
  CONSTRAINT `FKiiybq7a5k50ti6opsjo8yawb` FOREIGN KEY (`rmir_id`) REFERENCES `rmir_entries` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `task_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `description` text,
  `flash_time` bigint NOT NULL,
  `is_active` bit(1) NOT NULL,
  `priority` tinyint NOT NULL,
  `task_frequency` enum('DAILY','MONTHLY','QUARTERLY','WEEKLY','YEARLY') DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `creator_id` bigint NOT NULL,
  `main_assignee_id` bigint DEFAULT NULL,
  `next_run_date` date DEFAULT NULL,
  `recurrence_day` int DEFAULT NULL,
  `recurrence_month` int DEFAULT NULL,
  `final_date` date NOT NULL DEFAULT '2099-12-31',
  `start_date` date NOT NULL DEFAULT '2026-01-05',
  PRIMARY KEY (`id`),
  KEY `FKtu51qxipfrlul24901k9037y` (`creator_id`),
  KEY `FK6ps9vp0wmw31twrrux2ukosxc` (`main_assignee_id`),
  CONSTRAINT `FK6ps9vp0wmw31twrrux2ukosxc` FOREIGN KEY (`main_assignee_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `FKtu51qxipfrlul24901k9037y` FOREIGN KEY (`creator_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `task_template_chk_1` CHECK ((`priority` between 0 and 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tasks` (
  `task_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `description` text,
  `due_date` date DEFAULT NULL,
  `priority` enum('CRITICAL','HIGH','LOW','MEDIUM') DEFAULT NULL,
  `status` enum('CLOSED','CREATED','IN_PROGRESS','REVIEW','SUBMITTED') DEFAULT NULL,
  `task_no` varchar(255) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `updated_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `creator_id` bigint NOT NULL,
  `template_id` bigint DEFAULT NULL,
  PRIMARY KEY (`task_id`),
  UNIQUE KEY `UKm4w7lapbk8nwrgtq3yx6c9vqc` (`task_no`),
  KEY `FKt1ph5sat39g9lpa4g5kl46tbv` (`creator_id`),
  KEY `FK5iulee3u3e9o8f59i60qqkxnd` (`template_id`),
  CONSTRAINT `FK5iulee3u3e9o8f59i60qqkxnd` FOREIGN KEY (`template_id`) REFERENCES `task_template` (`id`),
  CONSTRAINT `FKt1ph5sat39g9lpa4g5kl46tbv` FOREIGN KEY (`creator_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `task_assignments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_type` enum('MAIN_ASSIGNEE','SUPPORTING') NOT NULL,
  `task_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKk36vhf9tt6t3woselwnkis6v6` (`task_id`),
  KEY `FKovnod7lqp56uups16si7jh6uu` (`user_id`),
  CONSTRAINT `FKk36vhf9tt6t3woselwnkis6v6` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`),
  CONSTRAINT `FKovnod7lqp56uups16si7jh6uu` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `task_comments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attachment_url` varchar(255) DEFAULT NULL,
  `changed_at` datetime(6) DEFAULT NULL,
  `content` text NOT NULL,
  `sender_id` bigint NOT NULL,
  `task_id` bigint NOT NULL,
  `message_type` enum('FILE','TEXT') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK2effnn390ok21yct6mryagwg5` (`sender_id`),
  KEY `FK9517viwn2geh1gpivj6l9y64u` (`task_id`),
  CONSTRAINT `FK2effnn390ok21yct6mryagwg5` FOREIGN KEY (`sender_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `FK9517viwn2geh1gpivj6l9y64u` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `task_status_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `new_status` enum('CLOSED','CREATED','IN_PROGRESS','REVIEW','SUBMITTED') DEFAULT NULL,
  `old_status` enum('CLOSED','CREATED','IN_PROGRESS','REVIEW','SUBMITTED') DEFAULT NULL,
  `timestamp` datetime(6) DEFAULT NULL,
  `changed_by` bigint NOT NULL,
  `task_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKhjg5v31h2idsrbqqorbos4cjw` (`changed_by`),
  KEY `FKlbsvojobaaf8bkuddoe7ig25s` (`task_id`),
  CONSTRAINT `FKhjg5v31h2idsrbqqorbos4cjw` FOREIGN KEY (`changed_by`) REFERENCES `users` (`user_id`),
  CONSTRAINT `FKlbsvojobaaf8bkuddoe7ig25s` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `template_assignees` (
  `template_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  KEY `FK5171mq4nod758xo9lh93pro0o` (`user_id`),
  KEY `FKpr746bvqb09sumf56c2u7i80c` (`template_id`),
  CONSTRAINT `FK5171mq4nod758xo9lh93pro0o` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `FKpr746bvqb09sumf56c2u7i80c` FOREIGN KEY (`template_id`) REFERENCES `task_template` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
