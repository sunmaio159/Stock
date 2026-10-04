# 04 API 契约（V1.3 修订版）

> 前后端唯一契约。
> V1.3 修订要点：① 新增偏好设置 API（API-01）；② 新增词典管理 CRUD + CSV 导入 API（API-02）；③ 新增数据源看板统计 API（API-03）；④ 统一定义写接口响应体（API-04）；⑤ 行情响应增加 `stale` 标识；⑥ 情绪指数增加 `samplePostCount`/`signalLevel`；⑦ 新增审计日志查询；⑧ 新增用户级免打扰清单。

---

## 0. 通用约定（V1.3 修订）

- 除登录/刷新外全部需 `Authorization: Bearer <AT>`
- 统一包装 `{code, message, data, traceId}`
- HTTP 恒 200，业务结果看 `code`
- **限流超限返回 HTTP 429 + code 1004**（传输层唯一非 200 状态码）**[V1.3 明确]**
- 限流默认 60 req/min/用户（Redis 滑窗）
- POST 写接口支持 `Idempotency-Key`（24h，存 Redis `idempotency:{key}` → 原始响应体，≤64KB）**[V1.3 明确]**
- **`traceId` 透传至 WS 消息** **[V1.3 新增]**

### 0.2 错误码总表（V1.3 增补）

| 码 | 含义 | 码 | 含义 |
|---|---|---|---|
| 0 | 成功 | 3003 | 验证码错误或已过期 |
| 1001/1002/1003 | 参数缺失/格式错误/资源不存在 | 3004 | 验证码请求过频 |
| 1004 | 限流 | 3005/3006 | 组内超限(100)/标的已存在 |
| 2001/2002/2003 | 未登录/Token过期/无权限 | 3007/3008 | 维度暂不可用/评分卡V3开放 |
| 3001/3002 | 自选股组超限(20)/告警DSL非法 | 3009/3010 | 规则数超限(50)/scope非法 |
| 4001-4005 | 源熔断/字段漂移/采集超时/重放窗口>7天/重放冲突 | **3011** | **词条已存在** |
| 5001-5003 | DB/缓存/调度异常 | **3012** | **回滚目标版本早于最早可用版本** |
| | | **4006** | **行情数据不可用** **[新增]** |
| | | **4007** | **24h 内重复重放同一源+窗口** **[新增]** |

### 0.3 写接口响应体统一约定（V1.3 新增，API-04）

1. 所有**创建类** POST 返回 `{code:0, data:{id:<新资源ID>}}`（最小集）
2. 所有**更新/删除类**返回 `{code:0, data:{id:<资源ID>, updatedAt:"..."}}`
3. 批量操作返回 `{code:0, data:{successCount:n, failedCount:m, failures:[{id,code,message}]}}`，部分失败时 `code` 仍为 0

---

## 1. 认证（不变）

### POST /api/auth/login
请求 `{phone?, email?, code, channel:"sms"|"email"}` [V1.3 扩展]
→ `{accessToken, refreshToken, user:{id,username,role,isNew}}`

### POST /api/auth/refresh / POST /api/auth/logout（不变）

---

## 2. M1 工作台与自选股

### GET /api/workbench?date=today
[V1.3 增补] 行情快照字段增加 `stale` 标识：
```json
{ "code":0, "data": {
  "watchlist": [ { "groupId":1, "groupName":"核心持仓",
    "items":[ { "code":"600519","name":"贵州茅台","price":1689.50,"changePercent":0.73,
                "sentimentIndex":72.3, "sentimentChange":-15.0, "surgeFlag":true,
                "priceStale":false } ] } ],  // [V1.3 新增]
  "marketSnapshot": { "shIndex":{"price":3088.12,"changePercent":0.5},
                      "stale":false, "updatedAt":"..." }  // [V1.3 新增]
} }
```

### 自选股接口（不变）
`GET/POST /api/watchlist/groups`, `POST/PATCH/DELETE /api/watchlist/items`, `PUT .../sort`

### 2.4 用户偏好设置（V1.3 新增，API-01）

#### `GET /api/user/preferences`
```json
{ "code":0, "data": {
  "colorScheme": "CN", "theme": "light",
  "pushQuietHours": { "start":"22:00", "end":"08:00", "minPriority":"P0" },
  "listDensity": "comfortable", "defaultWatchlistGroupId": 1,
  "updatedAt": "2026-10-03T09:00:00+08:00" } }
```

#### `PUT /api/user/preferences`
请求：`{colorScheme?, theme?, pushQuietHours?, listDensity?, defaultWatchlistGroupId?}`
→ `{updatedAt}`。未传字段回落默认值；`colorScheme`/`theme` 非枚举值 → 1002。
> `colorScheme` 服务端生效判断依据，前端不硬编码涨跌色。

---

## 3. M2 主数据（不变）

`GET /api/securities?keyword=&type=&industry=&page=&size=`
`GET /api/securities/{code}`
`GET /api/industries?type=1|2`

---

## 4. M3 采集运营（ADMIN，V1.3 增补）

### GET /api/crawl/sources（不变）

### POST /api/crawl/sources/{sourceCode}/circuit/reset（不变）

### POST /api/crawl/tasks/replay（V1.3 增强，C-01/P-03）
请求 `{sourceCode, startTime, endTime, target?:"parse"|"govern"}`
→ `{taskId, replayOf: <原任务ID>, totalCount, status:"RUNNING", bizId:"uuid"}`
错误：4004（窗口>7天）、4005（同源互斥）、**4007**（24h 内重复重放同一源+窗口）

### GET /api/crawl/tasks/replay/{taskId}
→ `{total, done, failed, recordFix, status, replayOf, indexRecalculated:[{securityCode,tradeDate}], finishedAt}`

### 4.2 词典管理（V1.3 新增，API-02，全部 ADMIN）

`GET /api/admin/dict/words?word=&dictType=1|2|3|4|5|6&status=&page=&size=`
`POST /api/admin/dict/words` → `{id, dictVersion}`（word ≤32, weight 0.01-9.99）
`PATCH /api/admin/dict/words/{id}` → `{id, dictVersion}`
`DELETE /api/admin/dict/words/{id}` → `{id, dictVersion}`

#### CSV 导入（两段式）
`POST /api/admin/dict/import/preview` (multipart 或 base64) → `{parsed, toAdd, toUpdate, toSkip, errors:[{line,reason}], previewToken}`
`POST /api/admin/dict/import/commit {previewToken}` → `{taskId, status:"RUNNING"}`
`GET /api/admin/dict/import/{taskId}` → `{total, done, failed, status, dictVersion}`

#### 词典版本
`GET /api/admin/dict/versions` → `{current, items:[{version, changeType, changeSummary, wordCount, operatorName, createdAt}]}`
`POST /api/admin/dict/versions/{version}/rollback` → `{dictVersion, rolledBackTo}`，错误：3012

### 4.3 数据源看板统计（V1.3 新增，API-03）

`GET /api/crawl/sources/{sourceCode}/stats?hours=24`
→ `{successRate, p95DurationMs, newRecords, zeroNewHours, fieldMissingRate:{title,content,authorId}, hourlyTrend:[{hour,count,success}], circuit:{state,failCount,openUntil}}`

`GET /api/crawl/overview`
→ `{healthScore, activeSources, totalSources, overallSuccessRate, overallP95Ms, last24hNew, deadLetterPending, driftP1Count24h}`

### 4.4 审计日志（V1.3 新增，C-02，ADMIN）

`GET /api/admin/audit?module=&action=&operatorId=&startDate=&endDate=&targetType=&targetId=&page=&size=`
→ `{items:[{id,operatorName,module,action,targetType,targetId,beforeValue,afterValue,bizId,ip,result,createdAt}], total}`
> `beforeValue/afterValue` 永不返回 `crawl_secret`/`password_hash`/`webhook_url` 等敏感字段。

---

## 5. M4/M5 舆情与情绪（V1.3 增强）

### GET /api/sentiment/posts（不变，明确排序键）
`ORDER BY published_at DESC, id DESC`（游标分页以此序编码 `nextCursor`）[V1.3 明确]

### GET /api/sentiment/index/{code}?startDate=&endDate=&granularity=day|15m
[V1.3 增强] 增加 `samplePostCount`：
```json
{ "code":0, "data": { "items": [{
  "tradeDate":"2026-10-03", "sentimentIndex":72.3, "postCount":127,
  "samplePostCount":95, "confidence":0.78,  // [V1.3 新增]
  "positiveRatio":0.62, "negativeRatio":0.18 }] } }
```

### GET /api/sentiment/surge（不变）

### GET /api/sentiment/wordcloud/{code}?date=（不变）

---

## 6. M6 个股详情（V1.3 增强）

### GET /api/securities/{code}/detail?cards=overview,sentiment
[V1.3 增强] overview 卡增加行情状态：
```json
{ "overview": {
  "code":"600519","name":"贵州茅台","price":1689.5,"changePercent":0.73,
  "priceStale":false,        // [V1.3 新增]
  "sentimentIndex":72.3, "heatPercentile":88,
  "signalLevel":"NORMAL",    // [V1.3 新增] NORMAL/HOT/COLD/NO_SIGNAL
  "compositeScore":null },
  ...
}
```

---

## 7. M8 告警（V1.3 增强）

### POST /api/alert-rules
[V1.3 修订] channels 元素升级：`["sys:wecom","in_app"]` 或 `["user:{channelId}"]`
规则 `user_id` 服务端注入，不接受客户端传入。

### GET /api/alert-events
[V1.3 修订] **服务端强制以当前登录 `user_id` 过滤**，不接受用户传入 `userId`。使用 `m8_alert_event.user_id` 直接过滤 + `idx_user_ack` 索引。

### 用户免打扰清单（V1.3 新增，C-10）
`GET /api/user/mute-list` → `{securities:[{code,reason}], sources:[{sourceCode}]}`
`PUT /api/user/mute-list` → `{updatedAt}`

---

## 8. WebSocket 协议（V1.3 增强）

连接、心跳、续期不变。

**V1 频道（V1.3 增强）**：

| 频道 | 消息体 | 频率 | 说明 |
|---|---|---|---|
| `/user/queue/quote` | `{msgId,traceId, quotes:[{securityCode,price,changePercent,volume,stale,timestamp}]}` | 3-5s | [V1.3] 每次推送带 `traceId`；`stale=true` 表示行情降级 |
| `/user/queue/alert` | `{msgId,traceId, alertId, ruleName, priority, securityCode, eventType, message, actionUrl, triggeredAt}` | 事件触发 | [V1.3] 带 `traceId` |
| `/user/queue/watchlist` | `{msgId,traceId, action, securityCode}` | 变更触发 | [V1.3] 带 `traceId` |

---

## 9. 版本与兼容（不变）

破坏性变更走 `/api/v2/...`，旧路径保留 ≥2 个版本周期；响应只增不删；废弃字段标 `deprecated` 保留 2 版；springdoc 自动生成 OpenAPI，`/swagger-ui` 仅内网。