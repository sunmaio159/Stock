# 细化二：数据库 Schema（可执行 DDL）

> **文档性质**：对分册 A §2.2.4 的补全——将 8 张核心表扩展为 **V1 全量 21 张表** 的可执行 DDL，含分区维护、淘汰任务、Flyway 迁移规范
> **环境**：MySQL 8.0+，InnoDB，utf8mb4，时区 `+08:00`，`sql_mode` 含 `STRICT_TRANS_TABLES`

---

## 1. 表清单与领域划分

| 领域 | 表 | 说明 |
|---|---|---|
| 用户域 | sys_user, sys_sms_code, m1_watchlist_group, m1_watchlist_item, m1_user_preference | 登录/自选/偏好 |
| 主数据域 | m2_security, m2_security_alias, m2_industry, m2_industry_member, m2_trade_calendar | 证券/行业/日历 |
| 采集域 | m3_source_config, m3_task_instance, m3_raw_record(分区), m3_drift_log | 源配置/任务/Raw Lake/漂移 |
| 治理域 | m4_normalized_doc, m4_entity_mention, m4_dead_letter | 治理产物/实体/死信 |
| 情感域 | m5_sentiment_dict, m5_sentiment_result, m5_sentiment_index, m5_sentiment_index_15m | 词典/结果/日指数/15min 指数 |
| 告警域 | m8_alert_rule, m8_alert_event | 规则/事件 |

> 评分卡相关（m6_factor_def / m6_composite_score）与行情资金基本面（m6_quote_daily / m6_capital_flow / 财报三表）属 V2/V3，DDL 见 §6 预留，V1 不建。

---

## 2. 用户域

```sql
CREATE TABLE `sys_user` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `phone`         varchar(16) NOT NULL COMMENT '手机号(登录账号)',
  `username`      varchar(32) NOT NULL DEFAULT '' COMMENT '昵称',
  `password_hash` varchar(128) DEFAULT NULL COMMENT '密码哈希(可选,验证码登录为主)',
  `status`        tinyint     NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
  `created_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

CREATE TABLE `sys_sms_code` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `phone`      varchar(16) NOT NULL,
  `code`       varchar(8)  NOT NULL COMMENT '验证码',
  `scene`      varchar(16) NOT NULL DEFAULT 'login' COMMENT '场景:login',
  `expires_at` datetime    NOT NULL,
  `used`       tinyint     NOT NULL DEFAULT 0,
  `created_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_phone_scene` (`phone`, `scene`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短信验证码';

CREATE TABLE `m1_watchlist_group` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `user_id`    bigint      NOT NULL,
  `group_name` varchar(32) NOT NULL,
  `sort_order` int         NOT NULL DEFAULT 0,
  `created_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自选股分组(每用户≤20组)';

CREATE TABLE `m1_watchlist_item` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `group_id`      bigint      NOT NULL,
  `security_code` varchar(16) NOT NULL,
  `note`          varchar(256) DEFAULT NULL COMMENT '备注',
  `sort_order`    int         NOT NULL DEFAULT 0,
  `added_at`      datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_code` (`group_id`, `security_code`),
  KEY `idx_code` (`security_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自选股明细(每组≤100只)';

CREATE TABLE `m1_user_preference` (
  `id`          bigint   NOT NULL AUTO_INCREMENT,
  `user_id`     bigint   NOT NULL,
  `preferences` json     NOT NULL COMMENT '{colorScheme,pushQuietHours,listDensity,theme,...}',
  `updated_at`  datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好';
```

## 3. 主数据域

```sql
CREATE TABLE `m2_security` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `security_code` varchar(16) NOT NULL COMMENT '6位代码,如600519',
  `security_name` varchar(32) NOT NULL,
  `exchange`      varchar(8)  NOT NULL COMMENT 'SH/SZ/BJ',
  `security_type` tinyint     NOT NULL DEFAULT 1 COMMENT '1股票 2指数 3基金 4债券',
  `status`        tinyint     NOT NULL DEFAULT 1 COMMENT '1上市 2退市 3暂停',
  `is_st`         tinyint     NOT NULL DEFAULT 0 COMMENT '0否 1ST 2*ST',
  `list_date`     date         DEFAULT NULL,
  `delist_date`   date         DEFAULT NULL,
  `created_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`security_code`),
  KEY `idx_type_status` (`security_type`, `status`),
  KEY `idx_name` (`security_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证券主数据';

-- 别名表:股吧常用简称/缩写,供AC自动机实体抽取与搜索
CREATE TABLE `m2_security_alias` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `security_code` varchar(16) NOT NULL,
  `alias`         varchar(32) NOT NULL COMMENT '简称/别名,如"茅台"',
  `alias_type`    tinyint     NOT NULL DEFAULT 1 COMMENT '1简称 2拼音 3俗名',
  `created_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alias` (`alias`, `security_code`),
  KEY `idx_code` (`security_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证券别名(AC自动机词典源)';

CREATE TABLE `m2_industry` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `industry_code` varchar(32) NOT NULL,
  `industry_name` varchar(64) NOT NULL,
  `industry_type` tinyint     NOT NULL COMMENT '1行业 2概念',
  `level`         tinyint     NOT NULL DEFAULT 1,
  `parent_code`   varchar(32)  DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_type` (`industry_code`, `industry_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行业/概念';

CREATE TABLE `m2_industry_member` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `security_code` varchar(16) NOT NULL,
  `industry_code` varchar(32) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sec_ind` (`security_code`, `industry_code`),
  KEY `idx_ind` (`industry_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行业/概念成分';

CREATE TABLE `m2_trade_calendar` (
  `id`         bigint NOT NULL AUTO_INCREMENT,
  `cal_date`   date   NOT NULL,
  `is_open`    tinyint NOT NULL COMMENT '1交易日 0休市',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date` (`cal_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易日历';
```

## 4. 采集域

```sql
CREATE TABLE `m3_source_config` (
  `id`                   bigint       NOT NULL AUTO_INCREMENT,
  `source_code`          varchar(32)  NOT NULL COMMENT '如EASTMONEY_GUBA',
  `source_name`          varchar(64)  NOT NULL,
  `source_type`          tinyint      NOT NULL COMMENT '1舆情 2新闻 3公告 4研报 5行情 6资金 7财报',
  `base_url`             varchar(512) NOT NULL,
  `cron`                 varchar(32)  NOT NULL DEFAULT '0 */5 * * * ?' COMMENT '采集周期',
  `rate_limit_qps`       int          NOT NULL DEFAULT 5,
  `circuit_state`        tinyint      NOT NULL DEFAULT 0 COMMENT '0闭合 1开路 2半开',
  `circuit_fail_count`   int          NOT NULL DEFAULT 0,
  `crawl_params`         json          DEFAULT NULL COMMENT '{ua,cookie,proxy,pageSize,schema:{...}}',
  `incremental_strategy` varchar(32)  NOT NULL DEFAULT 'last_cursor',
  `last_cursor`          varchar(128)  DEFAULT NULL,
  `status`               tinyint      NOT NULL DEFAULT 1,
  `created_at`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_code` (`source_code`),
  KEY `idx_type_status` (`source_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集源配置';

CREATE TABLE `m3_task_instance` (
  `id`           bigint      NOT NULL AUTO_INCREMENT,
  `source_code`  varchar(32) NOT NULL,
  `task_params`  json         DEFAULT NULL,
  `status`       tinyint     NOT NULL DEFAULT 0 COMMENT '0运行中 1成功 2失败 3部分成功',
  `retry_count`  int         NOT NULL DEFAULT 0,
  `record_count` int         NOT NULL DEFAULT 0 COMMENT '采集条数',
  `error_msg`    varchar(1024) DEFAULT NULL,
  `started_at`   datetime    NOT NULL,
  `finished_at`  datetime     DEFAULT NULL,
  `duration_ms`  int          DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_source_started` (`source_code`, `started_at`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集任务实例';

-- Raw Lake:按月RANGE分区;唯一键必须包含分区键(MySQL约束),故用 uk_source_outer_fetched
CREATE TABLE `m3_raw_record` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `task_id`      bigint       NOT NULL,
  `source_code`  varchar(32)  NOT NULL,
  `outer_id`     varchar(128) NOT NULL COMMENT '源端唯一ID',
  `raw_content`  longtext,
  `raw_headers`  text,
  `http_status`  int          NOT NULL,
  `content_hash` char(64) GENERATED ALWAYS AS (SHA2(`raw_content`,256)) STORED,
  `fetched_at`   datetime     NOT NULL,
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`, `fetched_at`),
  UNIQUE KEY `uk_source_outer_fetched` (`source_code`, `outer_id`, `fetched_at`),
  KEY `idx_task` (`task_id`),
  KEY `idx_fetched` (`fetched_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集原始记录(Raw Lake,保留30天)'
PARTITION BY RANGE (TO_DAYS(fetched_at)) (
  PARTITION p_init   VALUES LESS THAN (TO_DAYS('2026-10-01')),
  PARTITION p_202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
  PARTITION p_202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
  PARTITION p_max    VALUES LESS THAN MAXVALUE
);
-- 说明:分区表唯一键含分区键后,幂等去重改为"应用层先查后插 + 唯一键兜底(source+outer_id+当天)",
-- 跨天重复概率极低(同帖同outer_id跨天重复抓取视为新快照,允许更新互动数)。

CREATE TABLE `m3_drift_log` (
  `id`          bigint      NOT NULL AUTO_INCREMENT,
  `source_code` varchar(32) NOT NULL,
  `task_id`     bigint      NOT NULL,
  `drift_level` varchar(4)  NOT NULL COMMENT 'P1/P2/P3',
  `field_name`  varchar(64) NOT NULL,
  `detail`      varchar(512) DEFAULT NULL,
  `created_at`  datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_source_level` (`source_code`, `drift_level`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段漂移日志';
```

## 5. 治理域 + 情感域

```sql
CREATE TABLE `m4_normalized_doc` (
  `id`                bigint          NOT NULL AUTO_INCREMENT,
  `raw_record_id`     bigint          NOT NULL,
  `source_code`       varchar(32)     NOT NULL,
  `outer_id`          varchar(128)    NOT NULL,
  `security_code`     varchar(16)      DEFAULT NULL COMMENT '主标的(EntityWorker抽取)',
  `title`             varchar(512)     DEFAULT NULL,
  `content`           text            NOT NULL COMMENT '≤5000字',
  `author_id`         varchar(64)      DEFAULT NULL COMMENT '脱敏',
  `author_name`       varchar(64)      DEFAULT NULL,
  `interaction_count` int             NOT NULL DEFAULT 0 COMMENT '阅读+回复+点赞',
  `simhash`           bigint unsigned  DEFAULT NULL,
  `quality_score`     int             NOT NULL DEFAULT 3 COMMENT '1-5',
  `is_duplicate`      tinyint         NOT NULL DEFAULT 0,
  `topic_id`          varchar(32)      DEFAULT NULL COMMENT 'V2话题聚类',
  `published_at`      datetime         DEFAULT NULL,
  `replayed_at`       datetime         DEFAULT NULL COMMENT '重放时间',
  `created_at`        datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_outer` (`source_code`, `outer_id`),
  KEY `idx_security_published` (`security_code`, `published_at`),
  KEY `idx_simhash` (`simhash`),
  KEY `idx_quality` (`quality_score`, `is_duplicate`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治理后文档(V1按月归档DDL同构,见§7)';

CREATE TABLE `m4_entity_mention` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `doc_id`        bigint      NOT NULL,
  `security_code` varchar(16) NOT NULL,
  `mention_count` int         NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_code` (`doc_id`, `security_code`),
  KEY `idx_code` (`security_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档-实体关联';

CREATE TABLE `m4_dead_letter` (
  `id`         bigint       NOT NULL AUTO_INCREMENT,
  `stage`      varchar(16)  NOT NULL COMMENT 'clean/dedup/entity/quality/sentiment',
  `payload`    json         NOT NULL COMMENT '失败上下文',
  `error_msg`  varchar(1024) DEFAULT NULL,
  `retry_count` int         NOT NULL DEFAULT 0,
  `status`     tinyint      NOT NULL DEFAULT 0 COMMENT '0待处理 1已重试成功 2放弃',
  `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_stage` (`status`, `stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='治理/情感死信队列';

CREATE TABLE `m5_sentiment_dict` (
  `id`         bigint      NOT NULL AUTO_INCREMENT,
  `word`       varchar(32) NOT NULL,
  `dict_type`  tinyint     NOT NULL COMMENT '1正面 2负面 3否定 4程度副词 5转折',
  `weight`     decimal(4,2) NOT NULL DEFAULT 1.00 COMMENT '程度副词加权系数',
  `status`     tinyint     NOT NULL DEFAULT 1,
  `updated_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_word_type` (`word`, `dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='情感词典(后台可维护)';

CREATE TABLE `m5_sentiment_result` (
  `id`            bigint       NOT NULL AUTO_INCREMENT,
  `doc_id`        bigint       NOT NULL,
  `security_code` varchar(16)   DEFAULT NULL COMMENT '冗余加速',
  `engine_level`  tinyint      NOT NULL COMMENT '1规则 2模型 3LLM',
  `label`         varchar(8)   NOT NULL COMMENT 'positive/neutral/negative',
  `score`         decimal(5,4) NOT NULL COMMENT '[-1,1]',
  `confidence`    decimal(5,4) NOT NULL,
  `detail`        json          DEFAULT NULL COMMENT '命中词明细',
  `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_engine` (`doc_id`, `engine_level`),
  KEY `idx_security_created` (`security_code`, `created_at`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='情感分析结果';

CREATE TABLE `m5_sentiment_index` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `security_code`   varchar(16)  NOT NULL,
  `trade_date`      date         NOT NULL,
  `sentiment_index` decimal(6,2) NOT NULL COMMENT '[0,100]',
  `post_count`      int          NOT NULL DEFAULT 0,
  `positive_count`  int          NOT NULL DEFAULT 0,
  `neutral_count`   int          NOT NULL DEFAULT 0,
  `negative_count`  int          NOT NULL DEFAULT 0,
  `positive_ratio`  decimal(5,4) NOT NULL,
  `negative_ratio`  decimal(5,4) NOT NULL,
  `weight_detail`   json          DEFAULT NULL,
  `calculated_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_date` (`security_code`, `trade_date`),
  KEY `idx_date_index` (`trade_date`, `sentiment_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个股日情绪指数';

-- 板块指数:security_code 存 industry_code,另加 scope 字段区分
CREATE TABLE `m5_sentiment_index_15m` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `security_code`   varchar(16)  NOT NULL,
  `window_end`      datetime     NOT NULL COMMENT '15min窗口结束时间',
  `sentiment_index` decimal(6,2) NOT NULL,
  `post_count`      int          NOT NULL DEFAULT 0,
  `calculated_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_window` (`security_code`, `window_end`),
  KEY `idx_window` (`window_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='15min滚动情绪指数(保留7天)';
```

## 6. 告警域 + V2 预留

```sql
CREATE TABLE `m8_alert_rule` (
  `id`                   bigint      NOT NULL AUTO_INCREMENT,
  `user_id`              bigint      NOT NULL,
  `rule_name`            varchar(64) NOT NULL,
  `dsl`                  json        NOT NULL COMMENT '{scope,conditions:[{field,op,value,logic}],cooldown,maxPerDay,channels}',
  `scope`                varchar(128) NOT NULL COMMENT 'WATCHLIST:{groupId} | SECURITY:{code} | INDUSTRY:{code}',
  `cooldown_seconds`     int         NOT NULL DEFAULT 900,
  `max_per_day`          int         NOT NULL DEFAULT 10,
  `dedup_window_seconds` int         NOT NULL DEFAULT 900,
  `channels`             json        NOT NULL COMMENT '["wechat_work","in_app"]',
  `status`               tinyint     NOT NULL DEFAULT 1,
  `created_at`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则';

CREATE TABLE `m8_alert_event` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `rule_id`       bigint      NOT NULL,
  `security_code` varchar(16)  DEFAULT NULL,
  `event_type`    varchar(32) NOT NULL COMMENT 'price/surge/sentiment_reversal',
  `event_data`    json        NOT NULL,
  `dedup_key`     varchar(64) NOT NULL COMMENT 'rule+code+type+窗口,用于15min合并',
  `dedup_status`  tinyint     NOT NULL DEFAULT 0 COMMENT '0新发 1已合并',
  `push_status`   tinyint     NOT NULL DEFAULT 0 COMMENT '0待推送 1成功 2失败',
  `triggered_at`  datetime    NOT NULL,
  `pushed_at`     datetime     DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dedup` (`dedup_key`),
  KEY `idx_rule_triggered` (`rule_id`, `triggered_at`),
  KEY `idx_push_status` (`push_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件';
```

**V2 预留（V1 不执行，供 Flyway V2 脚本）**：`m6_quote_daily` / `m6_capital_flow` / 财报三表 / `m6_factor_def` / `m6_composite_score` —— DDL 沿用分册 A §2.2.4 第 8 张表及 ER 图定义，唯一调整：`m6_quote_daily` 加 `UNIQUE KEY uk_code_date (security_code, trade_date)` 并建议按月分区。

---

## 7. 分区与数据淘汰运维

```sql
-- 存储过程:每月1号新增下月分区(由调度任务调用)
DELIMITER //
CREATE PROCEDURE `sp_add_raw_partition`(IN p_month CHAR(7))  -- '2026-12'
BEGIN
  DECLARE p_name  VARCHAR(16) DEFAULT CONCAT('p_', REPLACE(p_month,'-',''));
  DECLARE p_bound VARCHAR(32) DEFAULT CONCAT(DATE_FORMAT(STR_TO_DATE(CONCAT(p_month,'-01'),'%Y-%m-%d') + INTERVAL 1 MONTH,'%Y-%m-%d'));
  SET @sql = CONCAT('ALTER TABLE m3_raw_record REORGANIZE PARTITION p_max INTO (',
                    'PARTITION ', p_name, ' VALUES LESS THAN (TO_DAYS(''', p_bound, ''')),',
                    'PARTITION p_max VALUES LESS THAN MAXVALUE)');
  PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
END //

-- 淘汰:30天前的分区直接DROP(每日凌晨执行)
CREATE PROCEDURE `sp_drop_expired_raw_partition`()
BEGIN
  -- 枚举 information_schema.PARTITIONS,凡分区上界 < NOW()-30天 则 DROP PARTITION
  -- (实现略,调度任务每日 03:30 调用)
END //
DELIMITER ;
```

| 数据 | 保留期 | 淘汰方式 | 执行时间 |
|---|---|---|---|
| m3_raw_record | 30 天 | DROP 月分区 | 每日 03:30 |
| m4_normalized_doc | 90 天 | `DELETE WHERE created_at < NOW()-90d`（V1 数据量小，先按批 1000 行删） | 每日 04:00 |
| m5_sentiment_result | 90 天 | 同上 | 每日 04:10 |
| m5_sentiment_index_15m | 7 天 | DELETE | 每日 04:20 |
| m5_sentiment_index | 永久 | 不淘汰 | — |
| m8_alert_event | 90 天 | DELETE | 每日 04:30 |
| sys_sms_code | 1 天 | DELETE | 每小时 |

## 8. Flyway 迁移规范

- 脚本目录 `db/migration`，命名 `V{版本}__{描述}.sql`，如 `V1.0__init.sql`、`V1.1__add_dead_letter.sql`
- **禁止**修改已合并脚本，只追加新脚本；DDL 变更必须幂等可重放（用 `IF NOT EXISTS` / 变更检查）
- 种子数据（证券主数据、行业、交易日历、情感词典初版）走 `R__seed_*.sql`（repeatable，UPSERT 方式）
- 本地/测试/生产同一套脚本，环境差异只走 `application-{env}.yml`

## 9. 容量估算（V1，6 个月）

| 表 | 日增行数 | 单行估算 | 6 个月体积 |
|---|---|---|---|
| m3_raw_record | ~6 万 | ~20KB（含原始报文） | ~20GB（30 天滚动窗口内 ~3.6GB） |
| m4_normalized_doc | ~5 万 | ~2KB | 90 天窗口 ~9GB |
| m5_sentiment_result | ~5 万 | ~0.5KB | 90 天窗口 ~2.3GB |
| m5_sentiment_index | ~5 千 | ~0.5KB | <100MB（永久） |
| 其余 | — | — | <500MB |

→ V1 MySQL 单机 50GB 盘余量充足；`m3_raw_record` 是唯一需要重点监控的表。
