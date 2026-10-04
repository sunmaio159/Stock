# 04 API 契约（定稿）

> 前后端唯一契约。通用约定：除登录/刷新外全部需 `Authorization: Bearer <AT>`；统一包装 `{code, message, data, traceId}`；HTTP 恒 200，业务结果看 `code`；限流默认 60 req/min/用户（Redis 滑窗），超限 HTTP 429 + code 1004；POST 写接口支持 `Idempotency-Key`（24h 重放返回首次结果）。

## 0. 错误码总表

| 码 | 含义 | 码 | 含义 |
|---|---|---|---|
| 0 | 成功 | 3003 | 验证码错误或已过期 |
| 1001/1002/1003 | 参数缺失/格式错误/资源不存在 | 3004 | 验证码请求过频 |
| 1004 | 限流 | 3005/3006 | 组内超限(100)/标的已存在 |
| 2001/2002/2003 | 未登录/Token过期/无权限 | 3007/3008 | 维度暂不可用/评分卡V3开放 |
| 3001/3002 | 自选股组超限(20)/告警DSL非法 | 3009/3010 | 规则数超限(50)/scope非法 |
| 4001-4005 | 源熔断/字段漂移/采集超时/重放窗口>7天/重放冲突 | 5001-5003 | DB/缓存/调度异常 |

## 1. 认证

### POST /api/auth/login
请求 `{phone, code}`。响应：
```json
{ "code": 0, "message": "success", "traceId": "a1b2c3",
  "data": { "accessToken": "eyJ...", "accessTokenTtl": 7200,
            "refreshToken": "d9f2...", "refreshTokenTtl": 604800,
            "user": { "id": 10001, "username": "股民甲", "role": "USER", "isNew": false } } }
```
错误：1002 / 3003 / 3004。

### POST /api/auth/refresh
请求 `{refreshToken}` → 新 AT + **新 RT（旧 RT 立即作废）**。复用旧 RT 返回 2002 并吊销该用户全部会话（防盗用）。

### POST /api/auth/logout —— 吊销 RT。

## 2. M1 工作台与自选股

### GET /api/workbench?date=today
Redis 缓存 5s；首屏 SLA P95≤1.5s；行情源故障时 `marketSnapshot=null` 且响应头 `X-Degraded: quote`，其余卡片照常。
```json
{ "code":0, "data": {
  "watchlist": [ { "groupId":1, "groupName":"核心持仓",
    "items":[ { "code":"600519","name":"贵州茅台","price":1689.50,"changePercent":0.73,
                "sentimentIndex":72.3, "sentimentChange":-15.0, "surgeFlag":true } ] } ],
  "sentimentSummary": { "topBullish":[{"code":"300750","name":"宁德时代","index":88.1}],
                        "topBearish":[{"code":"600519","name":"贵州茅台","index":41.2}] },
  "hotPosts": [ { "id":9001,"title":"...","sourceCode":"EASTMONEY_GUBA","securityCode":"600519",
                  "interactionCount":1523,"publishedAt":"2026-10-03T09:41:00+08:00" } ],
  "alerts": [ { "id":555,"ruleName":"茅台声量突增","priority":"P0","securityCode":"600519",
                "message":"...","triggeredAt":"...","actionUrl":"/stock/600519?tab=sentiment" } ],
  "marketSnapshot": { "shIndex":{"price":3088.12,"changePercent":0.5}, "updatedAt":"..." } } }
```
> 字段约束：`sentimentIndex` ∈ [0,100]，无数据为 `null`（前端显示 "—"）。

### 自选股
- `GET /api/watchlist/groups` → `[{id, groupName, itemCount, sortOrder}]`
- `POST /api/watchlist/groups` `{groupName(≤32), sortOrder?}` → 3001/1002
- `POST /api/watchlist/items` `{groupId, securityCode, note?}` → 3005/3006/1003
- `PATCH /api/watchlist/items/{id}` `{note?, sortOrder?}`
- `DELETE /api/watchlist/items/{id}`
- `PUT /api/watchlist/groups/{id}/sort` `{itemIds:[...]}` 整组排序（数组即新顺序）
- 写操作后：刷新 `watchlist:{userId}` 缓存 + WS 推送 `watchlist_change` 到该用户全部在线端

## 3. M2 主数据

- `GET /api/securities?keyword=&type=1&industry=&page=1&size=20` —— 命中优先级：代码精确 > 名称前缀 > 拼音前缀（`m2_security_alias`）> 名称包含；限流 20 req/min；P95 ≤200ms
  ```json
  { "code":0, "data": { "items":[ {"code":"600519","name":"贵州茅台","exchange":"SH",
      "pinyin":"GZMT","isSt":0,"industry":"白酒"} ], "total":1, "page":1, "size":20, "totalPages":1 } }
  ```
- `GET /api/securities/{code}` → `{code,name,exchange,type,isSt,listDate,industries:[{code,name,type}]}`；1003 不存在
- `GET /api/industries?type=1|2` → `[{code,name,level,memberCount}]`

## 4. M3 采集运营（ADMIN）

- `GET /api/crawl/sources` → `[{sourceCode,sourceName,sourceType,status,circuitState,circuitFailCount,todaySuccess,todayFail,todayAvgDurationMs,lastCursor,lastRunAt}]`
- `POST /api/crawl/sources/{sourceCode}/circuit/reset` —— 手动半开
- `POST /api/crawl/tasks/replay` `{sourceCode, startTime, endTime, target?:"parse"|"govern"}` → `{taskId,totalCount,status:"RUNNING"}`；窗口 ≤7 天（4004），同源互斥（4005）
- `GET /api/crawl/tasks/replay/{taskId}` → `{total,done,failed,status}`
- `GET /api/crawl/drift/{sourceCode}?dataType=` → 漂移历史（供运维看板）

## 5. M4/M5 舆情与情绪

### GET /api/sentiment/posts
参数：`securityCode`(必) / `startDate,endDate`(默认近7天,≤30天) / `label` / `minQuality`(默认2) / `cursor,size(≤50)`。
```json
{ "code":0, "data": { "items":[ {
    "id":9001, "sourceCode":"EASTMONEY_GUBA", "title":"...",
    "content":"...(摘录≤200字)", "originUrl":"https://guba.eastmoney.com/...",
    "authorName":"股海老***", "interactionCount":1523, "qualityScore":4,
    "sentiment": { "label":"positive", "score":0.82, "confidence":0.91 },
    "publishedAt":"2026-10-03T09:41:00+08:00" } ],
  "nextCursor":"eyJpZCI6OTAwMH0=", "hasMore":true } }
```
> 合规：`content` 只出摘录，`originUrl` 跳转源站原文；默认过滤 `is_duplicate=1`。

### GET /api/sentiment/index/{code}?startDate=&endDate=&granularity=day|15m
→ `[{tradeDate|windowEnd, sentimentIndex|null, postCount, positiveRatio, negativeRatio}]`；跨度 day≤180 天，15m 仅当日。

### GET /api/sentiment/surge?date=&securityCode=
→ `[{securityCode, surgeType:"ratio"|"z_score"|"reversal", metricValue, currentValue, baselineValue, triggeredAt, models:[...]}]`（V1 仅 ratio 有数据）

### GET /api/sentiment/wordcloud/{code}?date=
→ `{words:[{word, weight:0-100, sentiment}]}` Top100，Redis 缓存 30min。V1 = 关键词频率×质量加权；TopicWorker 聚类 V2。

## 6. M6 个股详情

### GET /api/securities/{code}/detail?cards=overview,sentiment
- `cards` 缺省返回七卡全部；每卡独立超时 500ms，聚合总预算 700ms；未接入维度返回 `{"available":false}`（V2 填充时向后兼容，只增字段）
- Redis 缓存 5min；首屏（overview+sentiment）P95 ≤800ms
```json
{ "code":0, "data": {
  "overview":  { "code":"600519","name":"贵州茅台","price":1689.5,"changePercent":0.73,
                 "sentimentIndex":72.3,"heatPercentile":88,"compositeScore":null },
  "sentiment": { "index":72.3,"indexChange":-15.0,"postCount":127,"positiveRatio":0.62,
                 "trend7d":[{"tradeDate":"...","sentimentIndex":70.1}],"surgeFlag":true,
                 "topPosts":[ "...3条" ] },
  "technical":   { "available": false },
  "capital":     { "available": false },
  "fundamental": { "available": false },
  "institutional": { "available": false },
  "risk": { "isSt":0, "recentAlerts":[ { "id":555,"eventType":"volume_surge","triggeredAt":"..." } ] } } }
```

### V2/V3 契约先行（冻结形状，V1 返回对应错误码）
- `POST /api/screener/run` `{conditions:[{dimension,field,operator,value}], logic:"AND"|"OR", page, size}` → V1 恒 3007
- `GET /api/scores/{code}?tradeDate=` → V1 恒 3008
- `GET /api/quotes/{code}/daily`、`GET /api/capital/{code}`、`GET /api/fundamentals/{code}` → V2 交付时补全契约

## 7. M8 告警

### POST /api/alert-rules
```json
{ "ruleName":"茅台情绪反转", "priority":"P1", "scope":"SECURITY:600519",
  "dsl": { "conditions":[ {"metric":"sentiment_index_1h","op":"drop","value":40,"windowMinutes":60} ] },
  "cooldownSeconds":900, "maxPerDay":10, "dedupWindowSeconds":900,
  "quietHours": { "start":"22:00","end":"08:00","minPriority":"P0" },
  "channels": ["wecom","in_app"] }
```
**指标白名单（V1）**：`price`（op: `>=`/`<=`）、`volume_ratio`（声量环比倍数，op `>=`）、`sentiment_index`（op `>=`/`<=`）。
**V2 增**：`sentiment_index_1h`（drop/surge）、`z_score_volume`、`composite_score`。
**op 白名单**：`> >= < <= = drop surge`。其他一律 3002。错误：3009（>50 条/用户）/ 3010。

- `GET /api/alert-rules` / `PATCH /api/alert-rules/{id}` `{status}` / `DELETE /api/alert-rules/{id}`
- `POST /api/alert-rules/test` `{dsl}` → 近 7 日数据回测：`{wouldTrigger, matchedStocks:[...], triggerTimes:[...]}`
- `GET /api/alert-events?startDate=&endDate=&status=&page=` → 含 `mergeCount/pushStatus/ackStatus`
- `PUT /api/alert-events/{id}/ack` `{ackStatus, note?}`；`PUT /api/alert-events/batch-ack` `{ids:[...]}`

## 8. WebSocket 协议

**连接**：`wss://host/ws`，STOMP 1.2，CONNECT 头 `Authorization: Bearer <AT>`，心跳 10s 双向，30s 无响应断开。
**续期**：AT 过期不断连；推送前服务端校验，过期推 `{type:"auth_expired"}`，客户端 60s 内 SEND 新 AT 续期，超时断开。
**V1 频道**：

| 频道 | 消息体 | 频率 |
|---|---|---|
| `/user/queue/quote` | `{msgId, quotes:[{securityCode,price,changePercent,volume,timestamp}]}` 自选股打包一帧 | 3-5s（仅交易日盘中） |
| `/user/queue/alert` | `{msgId, alertId, ruleName, priority, securityCode, eventType, message, actionUrl, triggeredAt}` | 事件触发 |
| `/user/queue/watchlist` | `{msgId, action:"ADD"|"REMOVE"|"SORT", securityCode}` | 变更触发 |

**可靠性**：每连接 `msgId` 单调递增，跳号 >3 触发重连；服务端 per-user 发送队列 100 条，溢出推 `{type:"overflow", dropped:N}`；前端指数退避重连（1s→2s→…→30s 封顶），重连后自动重订阅。
**降级**：WS 连续 3 次重连失败 → 前端切 10s 轮询 `/api/workbench`（仅取 watchlist+alerts）。

## 9. 版本与兼容

破坏性变更走 `/api/v2/...`，旧路径保留 ≥2 个版本周期；响应只增不删；废弃字段标 `deprecated` 保留 2 版；springdoc 自动生成 OpenAPI，`/swagger-ui` 仅内网。
