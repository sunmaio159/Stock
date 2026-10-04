# 03 API 契约缺口补全方案

> 对应总览 `00` 中的 API-01 ~ API-07。
> 定位：`04_API契约.md` 的**增补章节**，建议直接并入 04 后重新定稿为 V1.2。
> 覆盖 API-01 ~ API-07 及交叉评审新增的 C-01 / C-02 / C-03 / C-04 / C-10。

---

## 一、缺口总览

| 编号 | 缺口 | 严重度 | 现状 |
|---|---|---|---|
| API-01 | 用户偏好设置 API 全缺失 | P1 | 有表 `m1_user_preference`、有页面 `/settings/preferences`、是 MoSCoW Must 项 |
| API-02 | 词典管理 CRUD + CSV 导入 API 全缺失 | P1 | 有页面 `/admin/dictionary`、有表 `m5_sentiment_dict` |
| API-03 | 数据源看板"24h 新增趋势""字段缺失率"无 API | P1 | 07 §6 看板已列入 V1 |
| API-04 | 写接口响应体未定义 | P1 | `POST /api/watchlist/items`、`POST /api/alert-rules` 等 |
| API-05 | "HTTP 恒 200" 与限流 429 矛盾 | P2 | 04 §0 通用约定 |
| API-06 | 游标分页排序键未定义 | P2 | `GET /api/sentiment/posts` |
| API-07 | WS 消息无 traceId | P2 | 04 §8 |

---

## API-01 用户偏好设置（新增 §2.4）

### `GET /api/user/preferences`

```json
{ "code": 0, "message": "success", "traceId": "a1b2c3",
  "data": {
    "colorScheme": "CN",             // CN 红涨绿跌 | INTL 绿涨红跌
    "theme": "light",                // light | dark | auto
    "pushQuietHours": { "start": "22:00", "end": "08:00", "minPriority": "P0" },
    "listDensity": "comfortable",    // comfortable | compact
    "defaultWatchlistGroupId": 1,
    "updatedAt": "2026-10-03T09:00:00+08:00"
  } }
```

> 未设置过偏好时返回**系统默认值**（不返回 null），前端无需分支处理。

### `PUT /api/user/preferences`

请求（全量覆盖，未传字段回落为默认）：
```json
{ "colorScheme": "INTL", "theme": "dark",
  "pushQuietHours": { "start": "23:00", "end": "07:30", "minPriority": "P1" },
  "listDensity": "compact", "defaultWatchlistGroupId": 1 }
```
响应：`{ "code": 0, "data": { "updatedAt": "..." } }`
错误：`1002`（`colorScheme`/`theme` 非枚举值、`pushQuietHours` 时间格式非法）

**实现约束**：
- `colorScheme` 必须在**服务端**生效判断依据（07 §2 的涨跌色 token 由服务端下发的偏好决定，前端不硬编码）；
- `pushQuietHours` 是**用户级默认**，告警规则上的 `quietHours` 优先级更高（规则未配置时继承用户级）。此优先级关系当前 04/05 均未声明，需补。

---

## API-02 词典管理（新增 §4.2，全部 ADMIN）

### `GET /api/admin/dict/words`
参数：`word?`（模糊）`dictType?`（1正面 2负面 3否定 4程度副词 5转折 6广告词）`status?` `page` `size(≤100)`

```json
{ "code": 0, "data": { "items": [
    { "id": 101, "word": "利好", "dictType": 1, "weight": 1.20, "status": 1,
      "updatedAt": "2026-09-20T10:00:00+08:00" } ],
  "total": 860, "page": 1, "size": 100, "totalPages": 9 } }
```

### `POST /api/admin/dict/words`
请求 `{ word(≤32), dictType, weight(0.01-9.99), status? }`
响应 `{ "code":0, "data": { "id": 901, "dictVersion": 12 } }`
错误：`1002`（weight 越界 / dictType 非法）、`1003`、新增 `3011`（词条已存在）

### `PATCH /api/admin/dict/words/{id}`
请求 `{ weight?, status?, dictType? }` → `{ id, dictVersion }`

### `DELETE /api/admin/dict/words/{id}` → `{ id, dictVersion }`

### `POST /api/admin/dict/import`（CSV 批量导入）
请求：`multipart/form-data` 或 `{ "csvBase64": "...", "mode": "merge" }`
预览/执行两段式：

```json
POST /api/admin/dict/import/preview
→ { "code":0, "data": { "parsed": 320, "toAdd": 280, "toUpdate": 30, "toSkip": 10,
                          "errors": [ { "line": 12, "reason": "dictType 非法" } ],
                          "previewToken": "eyJ..." } }

POST /api/admin/dict/import/commit  { previewToken }
→ { "code":0, "data": { "taskId": 77, "status": "RUNNING" } }

GET  /api/admin/dict/import/{taskId}
→ { "code":0, "data": { "total":320, "done":320, "failed":0, "status":"SUCCESS", "dictVersion":13 } }
```

### `GET /api/admin/dict/versions` → 词典版本历史（配合 S-07）
```json
{ "code":0, "data": { "current": 13, "items": [
    { "version": 13, "changeType": "import", "changeSummary": "导入 280 词", "wordCount": 1140,
      "operatorName": "admin", "createdAt": "2026-10-03T10:00:00+08:00" } ] } }
```

### `POST /api/admin/dict/versions/{version}/rollback`
→ `{ "code":0, "data": { "dictVersion": 14, "rolledBackTo": 12 } }`
错误：新增 `3012`（回滚目标版本早于最早可用版本）

**实现约束（写入 05 §5.3.1）**：
1. 任何写操作成功后 **字典版本号 +1** 并写入 `m5_sentiment_dict_version`，再写 Redis 触发热加载；
2. 热加载为**双 buffer 原子切换**（与 AC 自动机一致），切换期间不影响正在计算的文档；
3. 导入/回滚为**异步任务**（万级词条同步执行会阻塞 ADMIN 请求），大文件必须走 preview→commit。

---

## API-03 数据源看板补充接口（新增 §4.3，ADMIN）

### `GET /api/crawl/sources/{sourceCode}/stats?hours=24`
```json
{ "code":0, "data": {
    "sourceCode": "EASTMONEY_GUBA",
    "successRate": 0.995, "p95DurationMs": 1820,
    "newRecords": 18234, "zeroNewHours": 0,
    "fieldMissingRate": { "title": 0.0, "content": 0.002, "authorId": 0.11 },
    "hourlyTrend": [ { "hour": "2026-10-03T09:00:00+08:00", "count": 920, "success": 918 } ],
    "circuit": { "state": 0, "failCount": 0, "openUntil": null } } }
```

### `GET /api/crawl/overview`
```json
{ "code":0, "data": {
    "healthScore": 96, "activeSources": 3, "totalSources": 4,
    "overallSuccessRate": 0.991, "overallP95Ms": 1650,
    "last24hNew": 52310, "deadLetterPending": 12, "driftP1Count24h": 0 } }
```

> `fieldMissingRate` 数据来源：采集时对每个声明字段记缺失计数（Redis 计数 + 每小时落库/或直接从 `m3_drift_log` P3 聚合）。需在 `05 §5.1` 的 Schema 校验环节补充"字段缺失率"统计埋点，否则该字段无数据源。

---

## API-04 写接口响应体统一定义

**问题**：04 中 `POST /api/watchlist/items`、`POST /api/alert-rules`、`PUT /api/watchlist/groups/{id}/sort` 等均无响应示例，前端无法知道新建资源的 `id`，联调必然返工。

**统一约定（新增 §0.3）**：
1. 所有**创建类** POST 返回 `{ "code":0, "data": { "id": <新资源ID> } }`（最小集，需要更多字段的端点单独列出）；
2. 所有**更新/删除类**返回 `{ "code":0, "data": { "id": <资源ID>, "updatedAt": "..." } }`；
3. 批量操作返回 `{ "code":0, "data": { "successCount": n, "failedCount": m, "failures":[{id, code, message}] } }`，**批量部分失败时 `code` 仍为 0**，由 `failedCount` 表达（不因个别失败整体报错）。

补充定义：

| 端点 | 响应体 |
|---|---|
| `POST /api/watchlist/groups` | `{ id, groupName, sortOrder, itemCount: 0 }` |
| `POST /api/watchlist/items` | `{ id, groupId, securityCode, securityName, note, sortOrder }` |
| `PATCH /api/watchlist/items/{id}` | `{ id, note, sortOrder, updatedAt }` |
| `DELETE /api/watchlist/items/{id}` | `{ id }` |
| `PUT /api/watchlist/groups/{id}/sort` | `{ groupId, itemIds: [...] }` |
| `POST /api/alert-rules` | `{ id, ruleName, priority, status, createdAt }` |
| `PATCH /api/alert-rules/{id}` | `{ id, status, updatedAt }` |
| `PUT /api/alert-events/{id}/ack` | `{ id, ackStatus, ackNote, ackAt }` |
| `PUT /api/alert-events/batch-ack` | `{ successCount, failedCount, failures:[] }` |
| `POST /api/auth/logout` | `{ "code":0, "data": {} }` |

---

## C-01 / C-02 / C-04 / C-10 契约增补（交叉评审新增）

### C-01：重放接口返回溯源字段

`POST /api/crawl/tasks/replay` 与 `GET /api/crawl/tasks/replay/{taskId}` 响应补 `replayOf`、`recordFix`：

```json
POST /api/crawl/tasks/replay
→ { "code":0, "data": { "taskId": 8801, "replayOf": 7712, "totalCount": 5230,
                        "status": "RUNNING", "bizId": "3f2a...uuid" } }

GET /api/crawl/tasks/replay/{taskId}
→ { "code":0, "data": { "total":5230, "done":5230, "failed":0, "recordFix": 4871,
                        "status": "SUCCESS", "replayOf": 7712,
                        "indexRecalculated": [ { "securityCode":"600519", "tradeDate":"2026-10-02" } ],
                        "finishedAt": "..." } }
```
错误码补充：**4007**（24h 内重复重放同一源+窗口，对应 C-01 防重）。

### C-02：审计日志查询（ADMIN）

`GET /api/admin/audit?module=&action=&operatorId=&startDate=&endDate=&targetType=&targetId=&page=&size=`

```json
{ "code":0, "data": { "items": [
    { "id": 9001, "operatorName":"admin", "module":"dict", "action":"rollback",
      "targetType":"dict_version", "targetId":"12",
      "beforeValue": { "version": 13, "wordCount": 1140 },
      "afterValue":  { "version": 14, "wordCount": 1080 },
      "bizId":"3f2a...uuid", "ip":"1.2.3.4", "result":1,
      "createdAt":"2026-10-03T10:00:00+08:00" } ],
  "total": 1, "page": 1, "size": 20, "totalPages": 1 } }
```
约束：仅 ADMIN；`beforeValue/afterValue` 中**永不返回** `crawl_secret`、`password_hash`、`webhook_url` 等敏感字段（服务端序列化前过滤）。

### C-04：告警规则版本

```json
GET /api/alert-rules/{id}/versions
→ { "code":0, "data": { "current": 3, "items": [
    { "version": 3, "changeNote":"阈值 40→35", "changedByName":"admin",
      "snapshot": { "dsl": {...}, "priority":"P1", "scope":"SECURITY:600519" },
      "createdAt":"..." } ] } }

POST /api/alert-rules/{id}/rollback  { "version": 2 }
→ { "code":0, "data": { "id": 55, "version": 4 } }
```
错误：`3015`（目标版本不存在）。规则每次 `PATCH` 自动 `version+1` 并写快照。

### C-10：用户级免打扰清单（告警降噪第四件套）

```json
GET /api/user/mute-list
→ { "code":0, "data": { "securityCodes":["600519"], "sources":["THS_GUBA"],
                         "eventTypes":["volume_surge"], "muteUntil": null } }

PUT /api/user/mute-list
{ "securityCodes":["600519"], "sources":[], "eventTypes":["volume_surge"],
  "muteUntil":"2026-10-10T23:59:59+08:00" }     // null = 长期
→ { "code":0, "data": { "updatedAt":"..." } }
```

**优先级顺序**（写入 05 §5.5）：`用户免打扰清单 > 规则 quietHours > 冷却 > 日上限 > 合并`。
免打扰命中时**不生成事件**（区别于冷却的"生成但抑制"），以便后续统计真实噪音量。

### C-03（契约侧）：情绪指数响应补可信度与"参考信号"语义

`GET /api/sentiment/index/{code}` 与详情页 `sentiment` 卡补充字段（落库设计见 04 号文件）：

```json
{ "tradeDate":"2026-10-03", "sentimentIndex":72.3, "postCount":127, "samplePostCount":86,
  "confidence":0.62, "signalLevel":"NORMAL",
  "baseline": { "window":"7d", "avg": 65.4, "stdDev": 8.1 },
  "positiveRatio":0.62, "negativeRatio":0.18 }
```

| 字段 | 语义 | 前端展示 |
|---|---|---|
| `samplePostCount` | 参与加权的有效样本数（排除无命中/低置信） | 与 `postCount` 差距大时提示"样本有效率低" |
| `confidence` | [0,1]，由样本量与离散度导出 | <0.3 显示"低置信"灰色标签 |
| `signalLevel` | `NORMAL` / `LOW_SAMPLE` / `HIGH_VOLATILE` / `NO_DATA` | 见 04 号文件 §C-03 |
| `baseline` | 同标的近 N 日均值与标准差 | 展示"高于/低于近期均值"，提供基准对比 |

**前端强制约束**（写入 07 §4）：情绪指数**任何展示位**必须带"参考信号"标识，禁止出现"买入/卖出/推荐"类措辞；`NO_DATA` 与 `LOW_SAMPLE` 不得渲染为 50 或 0。

---

## API-05 "HTTP 恒 200" 与限流 429 矛盾

`04 §0` 同段内既说"HTTP 恒 200，业务结果看 `code`"，又说"限流默认 60 req/min，超限 HTTP 429 + code 1004"。

**修正措辞**：

> HTTP 状态码恒为 200，**以下三类传输层例外除外**（此时响应体仍为标准包装）：
> - `429` 触发限流（code 1004）
> - `401` 网关层无法解析/验签 Token（业务层过期仍走 200 + code 2002）
> - `503` 优雅停机中的实例（响应头带 `X-Shutting-Down: 1`，前端据此切换重连）

同时补充：触发 429 时响应头必须带 `Retry-After`（秒），前端限流提示与退避策略依赖它。

---

## API-06 游标分页语义定义

`GET /api/sentiment/posts` 的 `nextCursor` 未定义排序键，深分页 + 时间范围过滤下会出现**漏项/重复项**。

**修正**：

```
排序键固定：ORDER BY published_at DESC, id DESC
游标编码：Base64(JSON{ "p": <ISO8601 published_at>, "i": <last id> })
下一页条件：published_at < :p OR (published_at = :p AND id < :i)
```

约束补充：
- `published_at IS NULL` 的文档（源未提供时间）**统一排在最末**，用 `created_at` 兜底排序，避免 NULL 排序在不同 MySQL 版本下行为不一致；
- `size` 默认 20、最大 50；
- 响应体补充 `hasMore`（已有）与**总数提示** `totalEstimate`（`EXPLAIN` 行数或按天估算，不必精确），前端据此决定是否展示"加载更多"。

---

## API-07 WebSocket 补充

1. **消息头统一带 `traceId`**：与 HTTP 一致，便于"某条告警推送失败"端到端排障；
2. **`/user/queue/quote` 增加行情陈旧标识**（配合 A-01）：

```json
{ "msgId": 1024, "traceId": "a1b2c3", "type": "quote",
  "quotes": [ { "securityCode":"600519", "price":1689.50, "changePercent":0.73,
                "volume": 1234567, "timestamp":"2026-10-03T10:15:03+08:00",
                "stale": false } ],
  "marketClosed": false }
```
`stale=true` 表示快照超过 60s 未更新（行情源异常），前端置灰显示；`marketClosed=true` 表示非交易时段，价格为最近收盘价。

3. **补一条服务端主动消息**（当前只有 `auth_expired` / `overflow`）：

| type | 触发 | 前端动作 |
|---|---|---|
| `auth_expired` | AT 过期 | 60s 内续期（已有） |
| `overflow` | 发送队列溢出 | 提示并触发一次全量拉取（已有） |
| `server_shutdown`（新增） | 优雅停机 | 立即进入轮询降级，不做重连风暴 |
| `resync_required`（新增） | 服务端重启/数据重建 | 前端丢弃本地缓存，重新拉全量 |

4. **重连后补发**：`msgId` 单调 + 跳号 >3 触发重连已有，但**重连成功后如何补齐跳过的消息未定义**。建议：重连时前端携带 `lastMsgId`，服务端从 per-user 环形缓冲（内存 200 条）补发缺失区间，超出则推 `resync_required`。

---

## 附：建议新增/变更错误码

| 码 | 含义 | 出处 |
|---|---|---|
| 3011 | 词典词条已存在 | API-02 |
| 3012 | 词典回滚目标版本无效 | API-02 |
| 3013 | 偏好设置枚举值非法 | API-01 |
| 4006 | 行情源不可用（价格类接口/告警降级） | A-01 |
| 3014 | 批量操作部分失败（配合 `failedCount`） | API-04 |
| 4007 | 24h 内重复重放同一源与窗口 | C-01 |
| 3015 | 告警规则回滚目标版本不存在 | C-04 |
