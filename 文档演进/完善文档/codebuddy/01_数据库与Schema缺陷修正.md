# 01 数据库与 Schema 缺陷修正方案

> 对应总览 `00` 中的 S-01 ~ S-13、A-03、OPS-03，以及交叉评审新增的 **C-01 / C-02 / C-04**。
> 所有语句按 **Flyway `V1.2__fix.sql`** 组织，脚本只增不改，可直接在 W0 执行。

---

## S-01（P0）`m8_alert_event` 缺 `user_id`

**问题**：事件表只有 `rule_id`，却声明了名为 `idx_user_unread` 的索引（字段里根本没有 `user_id`）。
后果：① `/api/alert-events` 按用户返回必须 `JOIN m8_alert_rule` 再过滤，索引全失效；② **越权校验无处落地**（只能先查出事件再回查规则判断归属，属于"先放行后校验"）；③ 07 §5 的"筛选 + 批量处理"在数据量上来后必然慢。

```sql
-- 1) 加列（先可空，避免长事务锁表；回填后改为 NOT NULL）
ALTER TABLE `m8_alert_event`
  ADD COLUMN `user_id` bigint DEFAULT NULL COMMENT '事件归属用户(冗余自 m8_alert_rule)' AFTER `rule_id`;

-- 2) 分批回填（生产环境用 LIMIT 循环，此处为示意）
UPDATE `m8_alert_event` e
  JOIN `m8_alert_rule` r ON r.`id` = e.`rule_id`
   SET e.`user_id` = r.`user_id`
 WHERE e.`user_id` IS NULL;

-- 3) 收窄为 NOT NULL
ALTER TABLE `m8_alert_event` MODIFY `user_id` bigint NOT NULL COMMENT '事件归属用户';

-- 4) 索引：删除名不副实的旧索引，换成真正的用户维度索引
ALTER TABLE `m8_alert_event` DROP KEY `idx_user_unread`;
ALTER TABLE `m8_alert_event` ADD KEY `idx_user_ack` (`user_id`, `ack_status`, `triggered_at`);
ALTER TABLE `m8_alert_event` ADD KEY `idx_user_type` (`user_id`, `event_type`, `triggered_at`);
```

**配套约束**：写入 `m8_alert_event` 的唯一入口必须从 `m8_alert_rule.user_id` 取值，禁止从请求参数取（防越权伪造）。
**配套修订**：`04 §7` `GET /api/alert-events` 明确"服务端强制以当前登录 `user_id` 过滤，不接受用户传入 `userId`"。

---

## S-02（P0）`m3_raw_record` 幂等维度错误 + 分区方案需重建

**问题**：
1. 唯一键 `uk_source_outer_fetched (source_code, outer_id, fetched_at)`，`fetched_at` 是 `DATETIME` 精确到**秒**。同一帖在同一天被采集两次（不同秒）会插入**两行**——注释里写的"当天内幂等由唯一键兜底"实际不成立，`06 §3` 所谓的"幂等链第一级"直接失效。
2. MySQL 强制"分区表的每个唯一键必须包含分区键列"，现方案分区键是 `TO_DAYS(fetched_at)`，因此想改成"按天幂等"就必须让唯一键含 `fetched_at`，而含秒级 `fetched_at` 又做不到按天幂等 —— **死锁**。
3. `content_hash` 是 STORED 生成列，全表无索引、无查询引用，却对每条 `longtext` 计算 SHA2，纯属白耗 CPU + 每行 64 字节。

**解法**：引入普通列 `fetched_date DATE`（应用层写入，非生成列），改用 `PARTITION BY RANGE COLUMNS(fetched_date)`。此时分区键 `fetched_date` 出现在所有唯一键中，MySQL 约束满足，且真正实现"当天幂等"。

```sql
-- 重建表（W0 阶段数据量为空/极小，直接重建；若已有数据则用 pt-osc 或建新表+改名）
DROP TABLE IF EXISTS `m3_raw_record_new`;

CREATE TABLE `m3_raw_record_new` (
  `id`           bigint       NOT NULL AUTO_INCREMENT,
  `task_id`      bigint       NOT NULL,
  `source_code`  varchar(32)  NOT NULL,
  `outer_id`     varchar(128) NOT NULL,
  `raw_content`  longtext,
  `http_status`  int          NOT NULL,
  `parse_status` tinyint      NOT NULL DEFAULT 0 COMMENT '0待解析 1已解析 2解析失败',
  `fetched_date` date         NOT NULL COMMENT '采集日(分区键+幂等维度,由应用层写入)',
  `fetched_at`   datetime     NOT NULL COMMENT '精确采集时刻(观测用)',
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`, `fetched_date`),
  UNIQUE KEY `uk_source_outer_day` (`source_code`, `outer_id`, `fetched_date`),
  KEY `idx_task` (`task_id`),
  KEY `idx_parse_status` (`parse_status`, `fetched_date`)   -- 支撑启动补偿扫描
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Raw Lake(30天,月分区)'
PARTITION BY RANGE COLUMNS(`fetched_date`) (
  PARTITION p_init   VALUES LESS THAN ('2026-10-01'),
  PARTITION p_202610 VALUES LESS THAN ('2026-11-01'),
  PARTITION p_202611 VALUES LESS THAN ('2026-12-01'),
  PARTITION p_max    VALUES LESS THAN (MAXVALUE)
);

RENAME TABLE `m3_raw_record` TO `m3_raw_record_old`, `m3_raw_record_new` TO `m3_raw_record`;
-- 如有存量数据：INSERT INTO m3_raw_record(... fetched_date ...) SELECT ..., DATE(fetched_at) FROM m3_raw_record_old;
```

**分区维护存储过程同步修订**（`03 §8` 原版用了 `TO_DAYS()`，在 `RANGE COLUMNS` 下不再适用）：

```sql
DROP PROCEDURE IF EXISTS `sp_add_raw_partition`;
DELIMITER //
CREATE PROCEDURE `sp_add_raw_partition`(IN p_month CHAR(7))
BEGIN
  DECLARE p_name  VARCHAR(16) DEFAULT CONCAT('p_', REPLACE(p_month,'-',''));
  DECLARE p_bound CHAR(10)    DEFAULT DATE_FORMAT(
      STR_TO_DATE(CONCAT(p_month,'-01'),'%Y-%m-%d') + INTERVAL 1 MONTH, '%Y-%m-%d');
  SET @sql = CONCAT(
    'ALTER TABLE m3_raw_record REORGANIZE PARTITION p_max INTO (',
    'PARTITION ', p_name, ' VALUES LESS THAN (''', p_bound, '''),',
    'PARTITION p_max VALUES LESS THAN (MAXVALUE))');
  PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
END //
DELIMITER ;
```

**新增：分区水位守护（对应 OPS-03，必须上线，否则数据全落 `p_max`）**
- 调度：每日 03:20 执行 `sp_add_raw_partition(下月)`，并为"下下月"兜底创建（防跨月漏跑）；
- 监控指标 `raw_partition_days_remaining`（= 最大非 `p_max` 分区上界 − 当前日期），< 3 天 → P0 告警；
- 淘汰任务改为：`ALTER TABLE m3_raw_record DROP PARTITION p_YYYYMM`（仅允许 DROP 非 `p_max` 分区，代码层硬校验）。

---

## S-03（P1）`m1_watchlist_item` 缺 `user_id`

**问题**：只有 `group_id`，告警 scope=`WATCHLIST:{groupId}` 展开、WS 订阅关系构建、"哪些用户关注了 600519"反查，都必须先 JOIN 分组表。

```sql
ALTER TABLE `m1_watchlist_item`
  ADD COLUMN `user_id` bigint DEFAULT NULL COMMENT '冗余自 m1_watchlist_group' AFTER `id`;

UPDATE `m1_watchlist_item` i
  JOIN `m1_watchlist_group` g ON g.`id` = i.`group_id`
   SET i.`user_id` = g.`user_id`
 WHERE i.`user_id` IS NULL;

ALTER TABLE `m1_watchlist_item` MODIFY `user_id` bigint NOT NULL;
ALTER TABLE `m1_watchlist_item` ADD KEY `idx_user_code` (`user_id`, `security_code`);
```

> 注意：**不要**加 `uk(user_id, security_code)` —— 用户可能把同一只股放进多个分组，唯一键会误拒。分组内去重由现有 `uk_group_code` 保证。

---

## S-04（P1）`m4_entity_mention` 无时间维度、无级联清理

**问题**：① 表无时间字段，90 天淘汰 `m4_normalized_doc` 后，mention 行成为孤儿且无法按时间清理；② 重放若用 `REPLACE INTO`（部分 ORM 默认行为）会改变 `doc_id`，旧 mention 全部变孤儿。

```sql
ALTER TABLE `m4_entity_mention`
  ADD COLUMN `published_at` datetime DEFAULT NULL COMMENT '冗余文档发布时间(便于按时间清理)' AFTER `security_code`,
  ADD KEY `idx_code_pub` (`security_code`, `published_at`);
```

**配套实现约束（写入 05 §5.2）**：
1. `m4_normalized_doc` 的幂等写入**只允许** `INSERT ... ON DUPLICATE KEY UPDATE`（保持 `id` 稳定），**严禁** `REPLACE INTO` / 先删后插；
2. 每日淘汰任务中，`m4_normalized_doc` 批删除后**同步按 `doc_id IN (已删批次)` 删除 mention**；
3. 新增"孤儿巡检"：每周扫描 `m4_entity_mention LEFT JOIN m4_normalized_doc` 无匹配的 `doc_id`，>1000 条告警并清理。

---

## S-05（P1）`m8_alert_channel` 无归属维度

**问题**：`uk_channel_name (channel, name)` 是全局唯一的系统级配置。V1 用户画像是"个人/小团队付费用户"，却无法配置自己的企微机器人 —— 只能共用运营的那一个，这在多人场景下既是体验问题也是**隐私泄露**（A 的告警推到 B 的群）。

```sql
ALTER TABLE `m8_alert_channel`
  ADD COLUMN `owner_type` tinyint NOT NULL DEFAULT 0 COMMENT '0系统级(全局共享) 1用户级' AFTER `id`,
  ADD COLUMN `user_id`    bigint  DEFAULT NULL COMMENT 'owner_type=1 时必填' AFTER `owner_type`;

ALTER TABLE `m8_alert_channel` DROP KEY `uk_channel_name`;
ALTER TABLE `m8_alert_channel` ADD UNIQUE KEY `uk_owner_channel_name` (`owner_type`, `user_id`, `channel`, `name`);
ALTER TABLE `m8_alert_channel` ADD KEY `idx_user` (`user_id`, `status`);
```

**配套规则**：`m8_alert_rule.channels` 的元素从 `["wecom"]` 升级为 `["sys:wecom"]` / `["user:{channelId}"]`；解析时按 `owner_type` 取对应 webhook。V1 最小实现：仅允许 `sys:*` + 本人 `user:*`。

---

## S-06（P1）备份策略与磁盘容量矛盾

**问题**：`03 §8` 容量估算——normalized_doc 9GB + sentiment_result 2.3GB + raw 3.6GB + 其他 ≈ **15GB+**；`06 §7` 要求"每日 `mysqldump` 全量 + 本地保留 7 天"，即 **≈105GB**，而机器只有 **50GB 盘**。备份要么撑爆磁盘，要么静默失败（最危险）。

**修正方案（选一，推荐 A）**：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A（推荐）** | **周全备 + 日增备**：每周日 03:00 全量 dump → 挂载的独立数据盘或对象存储（¥10-20/月）；每日仅 binlog 增量（已开启，7 天） | 存储量降至 ~20GB，RTO 略升但可接受 |
| B | 全量 dump 直接流式上传到对象存储，本地不留存 | 最省本地盘，依赖网络 |
| C | 扩容至 200GB 云盘（+¥40/月） | 最简单，成本小幅上升 |

**配套必须补的两条**：
1. **备份成功/失败必须有告警**（当前 06 §5 监控表里没有 `backup_*` 指标）——备份静默失败是最典型的"演练才发现没备份"；
2. 新增指标：`backup_last_success_hours`（>26h 告警）、`backup_size_bytes`（环比骤降 >30% 告警，防空备份）。

---

## S-07（P1）情感与指数缺版本追溯

**问题**：`m5_sentiment_dict` 热加载用 Redis 版本号，但 DB 无版本记录。词典迭代后无法回答"这次准确率变化是哪批词改的""10 月 3 日的指数是按哪版词典算的"，L2 训练集也会被不同口径的结果污染。

```sql
CREATE TABLE `m5_sentiment_dict_version` (
  `id`             bigint      NOT NULL AUTO_INCREMENT,
  `version`        bigint      NOT NULL COMMENT '单调递增,Redis 同步该值触发热加载',
  `change_type`    varchar(16) NOT NULL DEFAULT 'manual' COMMENT 'init/manual/import/rollback',
  `change_summary` varchar(256) DEFAULT NULL COMMENT '本次变更摘要(改了哪些词/导入行数)',
  `word_count`     int         NOT NULL DEFAULT 0,
  `operator_id`    bigint      DEFAULT NULL,
  `created_at`     datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version` (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='情感词典版本(可追溯/可回滚)';

ALTER TABLE `m5_sentiment_result`
  ADD COLUMN `dict_version` bigint      NOT NULL DEFAULT 0 COMMENT '计算所用词典版本' AFTER `requested_level`,
  ADD COLUMN `algo_version` varchar(16) NOT NULL DEFAULT 'v1' COMMENT '算法口径版本' AFTER `dict_version`;

ALTER TABLE `m5_sentiment_index`
  ADD COLUMN `algo_version`       varchar(16) NOT NULL DEFAULT 'v1' AFTER `calculated_at`,
  ADD COLUMN `dict_version`       bigint      NOT NULL DEFAULT 0    AFTER `algo_version`,
  ADD COLUMN `sample_post_count`  int         NOT NULL DEFAULT 0    COMMENT '实际参与加权样本数(排除无命中样本后)' AFTER `negative_ratio`;
```

**配套**：运营后台词典页提供"版本历史 + 一键回滚"（回滚 = 按上一版本快照重建词条）。

---

## S-08 ~ S-13（P2）批量清理与补列

```sql
-- S-08/09：删除无用的生成列与无效索引（减轻写入开销）
ALTER TABLE `m3_raw_record` DROP COLUMN `content_hash`;   -- 若已按上节重建则无需执行
ALTER TABLE `m4_normalized_doc` DROP KEY `idx_simhash`;   -- B-tree 无法做汉明距离检索

-- S-10：dedup_key 定长化，避免 utf8mb4 下超长与构造歧义
-- 约定：dedup_key = SHA1(ruleId|securityCode|eventType|windowStartEpochMinute) 的前 32 位十六进制
ALTER TABLE `m8_alert_event` MODIFY `dedup_key` char(32) NOT NULL COMMENT 'SHA1前32位:rule+code+type+窗口起点';

-- S-11：交易日历冗余上一交易日
ALTER TABLE `m2_trade_calendar`
  ADD COLUMN `prev_open_date` date DEFAULT NULL COMMENT '上一交易日(导入时计算,供突增/日频任务直接使用)';
-- 导入后回填：UPDATE c JOIN (SELECT cal_date, MAX(cal_date) OVER (ORDER BY cal_date ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) ...)
-- 简化回填 SQL：
UPDATE `m2_trade_calendar` c
  SET c.`prev_open_date` = (
    SELECT MAX(c2.`cal_date`) FROM (SELECT * FROM `m2_trade_calendar`) c2
     WHERE c2.`is_open` = 1 AND c2.`cal_date` < c.`cal_date`);

-- S-13：15min 派生表补 scope_type，为 V2 板块突增留基线
ALTER TABLE `m5_sentiment_index_15m`
  ADD COLUMN `scope_type` varchar(12) NOT NULL DEFAULT 'SECURITY' AFTER `id`;
ALTER TABLE `m5_sentiment_index_15m` DROP KEY `uk_code_window`;
ALTER TABLE `m5_sentiment_index_15m` ADD UNIQUE KEY `uk_scope_window` (`scope_type`, `security_code`, `window_end`);
```

- **S-12**（`m5_sentiment_result` 90 天淘汰 vs 指数永久保留）：在 `03 §8` 明确声明 —— **"情绪指数结果永久保留；其可重算性仅在源数据 90 天窗口内保证，超期指数作为不可重算的历史结论保存"**，避免后期误以为能回溯重算。

---

## C-01（P0）任务状态机不完整 + 重放无法溯源

> 来源：汇报版评审 §三.3 / §六方案二.1 —— "真正要做的不是存数据，而是可追溯数据"。

**问题**：`m3_task_instance.status` 仅 `0运行中/1成功/2失败/3部分成功`，而汇报版要求的状态机含**待重试、已回放**。更关键的是：**重放任务与正常采集任务写在同一张表却无法区分**，导致 ①无法统计"本月重放了多少次、修复了多少数据"；②重放产生的数据与正常数据混在一起，对账时分不清差异来自采集失败还是重放；③重复重放无防重。

```sql
ALTER TABLE `m3_task_instance`
  MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 0
    COMMENT '0运行中 1成功 2失败 3部分成功 4待重试 5已放弃 6回放中 7回放完成 8回放失败',
  ADD COLUMN `replay_of`  bigint     DEFAULT NULL COMMENT '重放源任务ID(非空=本次为重放任务)' AFTER `source_code`,
  ADD COLUMN `retry_of`   bigint     DEFAULT NULL COMMENT '重试来源任务ID' AFTER `replay_of`,
  ADD COLUMN `biz_id`     char(36)   DEFAULT NULL COMMENT '全局业务追踪ID(UUID,串联采集→治理→情感→告警)' AFTER `retry_of`,
  ADD COLUMN `record_new` int        NOT NULL DEFAULT 0 COMMENT '本次新增记录数' AFTER `record_count`,
  ADD COLUMN `record_fix` int        NOT NULL DEFAULT 0 COMMENT '本次修复记录数(仅重放任务有意义)' AFTER `record_new`,
  ADD KEY `idx_replay` (`replay_of`),
  ADD KEY `idx_biz`    (`biz_id`),
  ADD KEY `idx_status_started` (`status`, `started_at`);
```

**配套实现约束**：
1. 重放任务创建时必填 `replay_of = 源任务ID`，`status` 走 `6 → 7/8`，**不复用 0/1/2/3**；
2. 重试任务 `retry_of` 指向上一次，`status` 从 `4待重试` → `0运行中`，重试超上限置 `5已放弃` 并进死信；
3. **重放防重**：同一 `(source_code, replay_of, 时间窗口)` 在 24h 内不允许重复提交（对应现有 4005 同源互斥，扩展到重放维度）；
4. 数据源看板新增"重放次数/修复记录数"两列，数据来自 `record_fix`。

---

## C-02（P0）审计能力为零

> 来源：汇报版评审 §三.3 / §六方案二.3。本方案 `07 §1` 把 `/admin/audit` 排到了 **V3**，但词典热更新、熔断重置、重放这些**高风险操作从 W3 起就在用**——审计不能等到 V3。

```sql
CREATE TABLE `sys_audit_log` (
  `id`            bigint      NOT NULL AUTO_INCREMENT,
  `operator_id`   bigint      DEFAULT NULL COMMENT '操作人(NULL=系统/调度)',
  `operator_name` varchar(32) DEFAULT NULL,
  `module`        varchar(32) NOT NULL COMMENT 'dict/source/alert/replay/watchlist/user/auth/system',
  `action`        varchar(32) NOT NULL COMMENT 'create/update/delete/import/rollback/reset/replay/login/logout',
  `target_type`   varchar(32) DEFAULT NULL COMMENT 'dict_word/source_config/alert_rule/raw_record/...',
  `target_id`     varchar(64) DEFAULT NULL,
  `before_value`  json        DEFAULT NULL COMMENT '变更前快照(敏感字段只存指纹,不存明文)',
  `after_value`   json        DEFAULT NULL,
  `biz_id`        char(36)    DEFAULT NULL COMMENT '串联同一操作的全链路',
  `ip`            varchar(64) DEFAULT NULL,
  `result`        tinyint     NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
  `fail_reason`   varchar(256) DEFAULT NULL,
  `created_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_module_created` (`module`, `created_at`),
  KEY `idx_operator` (`operator_id`, `created_at`),
  KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志(保留180天,每日批DELETE)';
```

**V1 必须埋点的 8 类操作**（其余可延后）：

| module | action | 触发点 |
|---|---|---|
| `auth` | login / logout | 04 §1 |
| `dict` | create / update / delete / import / **rollback** | 03 号文件 API-02 |
| `source` | **reset**（熔断手动半开）/ update（改 QPS、改 cookie） | 04 §4 |
| `replay` | replay（含窗口与影响面） | 04 §4 |
| `alert` | create / update / delete（规则变更） | 04 §7 |
| `system` | 词典热加载完成、分区新增、淘汰执行 | 06 调度 |

**约束**：审计写入**必须与业务操作同事务**（词典改词 + 写审计一条事务），否则出现"改了但没记录"；写入失败不得阻断业务（降级为 WARN 日志 + `audit_write_fail_total` 指标）。

---

## C-04（P1）版本治理补齐（规则 / Schema / 标注集）

本轮 S-07 只补了**词典版本**。汇报版 §六方案二.2 要求完整的规则版本管理，补齐另外三类：

```sql
-- 1) 告警规则版本：白名单升级后能识别旧规则数据
ALTER TABLE `m8_alert_rule`
  ADD COLUMN `version`    int    NOT NULL DEFAULT 1 COMMENT '规则版本(每次变更+1)' AFTER `status`,
  ADD COLUMN `updated_by` bigint DEFAULT NULL AFTER `version`;

CREATE TABLE `m8_alert_rule_version` (
  `id`         bigint   NOT NULL AUTO_INCREMENT,
  `rule_id`    bigint   NOT NULL,
  `version`    int      NOT NULL,
  `snapshot`   json     NOT NULL COMMENT '规则完整快照(dsl/scope/降噪/渠道)',
  `changed_by` bigint   DEFAULT NULL,
  `change_note` varchar(256) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_version` (`rule_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则版本快照(可回滚/可解释历史告警)';

-- 2) 源 Schema 版本：字段漂移被人工确认后升版本，作为重放与校验的基准
ALTER TABLE `m3_source_config`
  ADD COLUMN `schema_version` int NOT NULL DEFAULT 1 COMMENT '字段结构版本(漂移确认修复后+1)' AFTER `crawl_params`;
ALTER TABLE `m3_drift_log`
  ADD COLUMN `schema_version` int NOT NULL DEFAULT 0 COMMENT '发现漂移时的当前schema版本' AFTER `drift_level`,
  ADD COLUMN `handled` tinyint NOT NULL DEFAULT 0 COMMENT '0未处理 1已确认 2已修复' AFTER `schema_version`;

-- 3) 标注集版本：准确率指标必须绑定到"哪一版标注集 + 哪一版标注规范"
CREATE TABLE `m5_label_set` (
  `id`                bigint      NOT NULL AUTO_INCREMENT,
  `version`           int         NOT NULL COMMENT '标注集版本',
  `name`              varchar(64) NOT NULL,
  `sample_count`      int         NOT NULL DEFAULT 0,
  `guideline_version` varchar(16) NOT NULL DEFAULT 'g1' COMMENT '标注规范版本',
  `annotators`        varchar(128) DEFAULT NULL,
  `kappa`             decimal(4,3) DEFAULT NULL COMMENT '双人一致率(≥0.7 方可作验收基线)',
  `status`            tinyint     NOT NULL DEFAULT 0 COMMENT '0构建中 1可用 2归档',
  `created_at`        datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version` (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工标注集版本(准确率验收基准)';

CREATE TABLE `m5_label_item` (
  `id`           bigint      NOT NULL AUTO_INCREMENT,
  `set_id`       bigint      NOT NULL,
  `doc_id`       bigint      NOT NULL,
  `human_label`  varchar(8)  NOT NULL COMMENT 'positive/neutral/negative',
  `l1_label`     varchar(8)  DEFAULT NULL COMMENT '回归时填入L1结果',
  `l1_score`     decimal(5,4) DEFAULT NULL,
  `agree`        tinyint     DEFAULT NULL COMMENT '1一致 0不一致',
  `created_at`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_set_doc` (`set_id`, `doc_id`),
  KEY `idx_agree` (`set_id`, `agree`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标注明细(词典变更回归用)';
```

**配套流程**（写入 08 §1 数据准确性验证）：
1. 词典每次变更（含导入/回滚）→ 自动跑**当前标注集回归** → 写入 `m5_label_item.l1_label/agree` → 输出准确率变化；
2. 准确率环比**下降 >3 个百分点** → 阻断发布并提示回滚（这就是汇报版要求的"规则更新后的回归标准"）；
3. 准确率报告必须标注 `标注集版本 + 标注规范版本 + 词典版本 + 算法版本`，四者齐全才可复现。

---

## A-03（P1）`crawl_params` 中的 cookie/ua 明文存储

**问题**：`04 §0` 与 `02 §五` 只声明 webhook URL 加密，`m3_source_config.crawl_params` JSON 里的 `cookie`/`ua` 明文入库。该 cookie 通常是源站登录态，泄露等同于账号被盗用，且 `06 §7` 的备份会把明文带到备份文件里。

**修正**：
1. `crawl_params` 拆分为 `crawl_params`（非敏感：pageSize/schema/端点选择）+ `crawl_secret`（敏感：`ua`/`cookie`，应用层 AES-GCM 加密，密钥走环境变量 `AQUILA_SECRET_KEY`，不入代码库、不入备份）；
2. 新增 `secret_updated_at` 字段，>30 天未轮换 → 运营后台提示；
3. 备份脚本排除 `crawl_secret` 列，或整表 dump 但单独加密存放。

```sql
ALTER TABLE `m3_source_config`
  ADD COLUMN `crawl_secret`     varbinary(1024) DEFAULT NULL COMMENT 'AES-GCM加密(ua/cookie)' AFTER `crawl_params`,
  ADD COLUMN `secret_updated_at` datetime       DEFAULT NULL AFTER `crawl_secret`;
```

---

## 附：Flyway 脚本编排建议

```
V1.0__init.sql          原 22 表（须先按 S-02 修正 m3_raw_record 后再固化）
V1.1__alert_channel.sql 原告警渠道表
V1.2__fix_p0.sql        S-01(m8_alert_event) + S-02(m3_raw_record 重建) + S-03/S-04/S-05 加列
V1.3__fix_version.sql   S-07(词典版本表 + 结果/指数版本列) + A-03(crawl_secret) + S-11/S-13
V1.4__quote_daily.sql   A-01 行情（提前启用 m6_quote_daily，见 02 号文件）
V1.5__governance.sql    C-01(m3_task_instance 状态机/replay_of/biz_id) + C-02(sys_audit_log)
                        + C-04(规则版本/schema_version/标注集版本)
R__seed_trade_calendar_prev.sql  回填 prev_open_date
R__seed_dict_version.sql         初始化字典版本 1
```

**表数量变化**：22 张（V1.1）→ **28 张**（V1.2）
新增：`m5_sentiment_dict_version`、`m5_author_profile`、`m5_label_set`、`m5_label_item`、`m8_alert_rule_version`、`sys_audit_log`；提前启用 `m6_quote_daily`（原 V2 预留）。
