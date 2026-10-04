# 细化三：API 契约细节（V1 全量端点）

> **文档性质**：对分册 A §2.3 的逐项展开——每个端点含请求参数表、完整响应示例、错误码、鉴权与限流
> **通用约定**（不再逐端点重复）：
> - 鉴权：除 API01 外全部需 `Authorization: Bearer <AT>`；AT 过期返回 `2002`，前端静默用 RT 刷新（`POST /api/auth/refresh`）
> - 响应包装：`{code, message, data, traceId}`；HTTP 状态码恒为 200，业务结果看 `code`
> - 限流：默认单用户 60 req/min（Redis 滑动窗口），超限返回 `429` HTTP 状态 + code 1004
> - 幂等：POST 写接口支持请求头 `Idempotency-Key`，24h 内重放返回首次结果

---

## 1. M1 账户与工作台

### API01 登录 `POST /api/auth/login`

| 参数 | 类型 | 必填 | 说明 |
|---|---|:-:|---|
| phone | string | ✅ | 手机号，正则 `^1\d{10}$` |
| code | string | ✅ | 短信验证码（开发环境万能码 `8888`） |

**响应 200**：
```json
{
  "code": 0, "message": "success", "traceId": "a1b2c3",
  "data": {
    "accessToken": "eyJhbG...", "accessTokenTtl": 7200,
    "refreshToken": "d9f2...", "refreshTokenTtl": 604800,
    "user": { "id": 10001, "username": "股民甲", "isNew": false }
  }
}
```
**错误**：`1002` 验证码格式错误 / `3003` 验证码错误或已过期 / `3004` 验证码请求过频（同号 1 条/min，10 条/天）

### API02 工作台聚合 `GET /api/workbench?date=today`

| 参数 | 类型 | 必填 | 说明 |
|---|---|:-:|---|
| date | string | 否 | `today`（默认）或 `yyyy-MM-dd`（仅允许查交易日） |

```json
{ "code": 0, "message": "success", "traceId": "...",
  "data": {
    "watchlist": [ { "groupId": 1, "groupName": "核心持仓",
      "items": [ { "code": "600519", "name": "贵州茅台", "price": 1689.50, "changePercent": 0.73,
                   "sentimentIndex": 72.3, "sentimentChange": -15.0, "surgeFlag": true } ] } ],
    "sentimentSummary": { "topBullish": [{"code":"300750","name":"宁德时代","index":88.1}],
                          "topBearish": [{"code":"600519","name":"贵州茅台","index":41.2}] },
    "hotPosts": [ { "id": 9001, "title": "...", "sourceCode": "EASTMONEY_GUBA",
                    "securityCode": "600519", "interactionCount": 1523, "publishedAt": "..." } ],
    "announcements": [],
    "alerts": [ { "id": 555, "ruleName": "茅台声量突增", "securityCode": "600519",
                  "message": "...", "triggeredAt": "...", "actionUrl": "/securities/600519" } ],
    "marketSnapshot": { "shIndex": {"price":3088.12,"changePercent":0.5}, "updatedAt": "..." }
  } }
```
缓存：Redis 5s；首屏 SLA P95 ≤ 1.5s。**降级**：行情源故障时 `marketSnapshot=null` 且响应头 `X-Degraded: quote`，其余卡片照常。

### API03-05 自选股

- `GET /api/watchlist/groups` → `data: [{id, groupName, itemCount, sortOrder}]`
- `POST /api/watchlist/groups` body `{groupName(≤32), sortOrder?}`；错误 `3001` 组数超限（>20）/ `1002` 名称非法
- `POST /api/watchlist/items` body `{groupId, securityCode, note?}`；错误 `3005` 组内超限（>100）/ `3006` 标的已存在 / `1003` 证券代码不存在

**写扩散同步**：任一写操作后刷新 Redis `watchlist:{userId}` 并异步落库（分册 B §3.7.6）。

---

## 2. M2 标的主数据

### API06 搜索 `GET /api/securities?keyword=茅台&industry=&type=1&page=1&size=20`

| 参数 | 说明 |
|---|---|
| keyword | 代码/名称/拼音前缀，命中优先级：代码精确 > 名称前缀 > 拼音前缀 > 名称包含 |
| type | 1股票(默认) 2指数 3基金 |
| industry | 行业代码过滤 |

```json
{ "code":0, "data": { "items":[ {"code":"600519","name":"贵州茅台","exchange":"SH","pinyin":"GZMT","isSt":0,"industry":"白酒"} ],
  "total": 1, "page": 1, "size": 20, "totalPages": 1 } }
```
限流：20 req/min（防爬）。P95 ≤ 200ms。

### API07 证券详情 `GET /api/securities/{code}`
→ `{code, name, exchange, type, isSt, listDate, industries:[{code,name,type}]}`；不存在返回 `1003`。

### API08 行业列表 `GET /api/industries?type=concept`
→ `[{code, name, level, memberCount}]`；type：1行业 2概念。

---

## 3. M3 采集运营（需 `ROLE_ADMIN`）

### API09 `GET /api/crawl/sources`
→ `[{sourceCode, sourceName, sourceType, status, circuitState, circuitFailCount, todaySuccess, todayFail, todayAvgDurationMs, lastCursor, lastRunAt}]`

### API10 重放 `POST /api/crawl/tasks/replay`

| 参数 | 必填 | 说明 |
|---|:-:|---|
| sourceCode | ✅ | 目标源 |
| startTime / endTime | ✅ | 重放窗口，跨度 ≤ 7 天 |
| target | 否 | `parse`(默认，重新解析+治理) / `govern`（仅治理） |

```json
{ "code":0, "data": { "taskId": 88001, "totalCount": 15230, "status": "RUNNING" } }
```
**错误**：`4004` 重放窗口超 7 天 / `4005` 该源已有重放任务运行中（同源互斥）。
进度查询：`GET /api/crawl/tasks/replay/{taskId}` → `{total, done, failed, status}`。

---

## 4. M4/M5 舆情与情绪

### API11 帖文流 `GET /api/sentiment/posts`

| 参数 | 说明 |
|---|---|
| securityCode | 必填 |
| startDate/endDate | 默认近 7 天，跨度 ≤ 30 天 |
| label | positive/neutral/negative 过滤 |
| minQuality | 1-5，默认 2（过滤水军） |
| cursor/size | 游标分页，size ≤ 50 |

```json
{ "code":0, "data": { "items":[ {
    "id": 9001, "sourceCode": "EASTMONEY_GUBA", "title": "...", "content": "...(≤200字摘要,全文V2)",
    "authorName": "股海老***", "interactionCount": 1523, "qualityScore": 4,
    "sentiment": { "label": "positive", "score": 0.82, "confidence": 0.91 },
    "publishedAt": "2026-10-03T09:41:00+08:00" } ],
  "nextCursor": "eyJpZCI6OTAwMH0=", "hasMore": true } }
```
cursor = base64(`{id, publishedAt}`)，服务端校验防篡改。默认过滤 `is_duplicate=1`。

### API12 情绪指数趋势 `GET /api/sentiment/index/{code}?startDate=&endDate=&granularity=day`
→ `[{tradeDate, sentimentIndex, postCount, positiveRatio, negativeRatio}]`；granularity=day（V1）/15m（盘中）。跨度 ≤ 180 天。

### API13 突增事件 `GET /api/sentiment/surge?date=&securityCode=`
→ `[{securityCode, surgeType("z_score"|"ratio"|"reversal"), zScore, currentValue, baselineValue, sentimentIndex, triggeredAt, models:[]}]`

### API14 词云 `GET /api/sentiment/wordcloud/{code}?date=`
→ `{words:[{word, weight(0-100), sentiment}]}`，Top 100 词，Redis 缓存 30min。**V1 简化**：基于 Entity/关键词频率统计，TopicWorker 聚类留 V2。

---

## 5. M6 投研

### API15 个股详情七卡片 `GET /api/securities/{code}/detail`

```json
{ "code":0, "data": {
  "overview":    { "code":"600519","name":"贵州茅台","price":1689.5,"changePercent":0.73,"compositeScore":null },
  "sentiment":   { "index":72.3,"indexChange":-15.0,"postCount":127,"positiveRatio":0.62,
                   "trend7d":[...],"surgeFlag":true,"topPosts":[...(3条)] },
  "technical":   { "available": false },
  "capital":     { "available": false },
  "fundamental": { "available": false },
  "institutional": { "available": false },
  "risk":        { "isSt":0,"delistRisk":false,"recentAlerts":[...] } } }
```
**V1 约定**：未接入维度返回 `{available:false}` 而非 404/空对象，前端据此渲染"敬请期待"占位；字段结构 V2 填充时向后兼容。Redis 缓存 5min；首屏（overview+sentiment）与次级卡片支持 `?cards=overview,sentiment` 按需加载。

### API19 选股器（V2，契约先行冻结）`POST /api/screener/run`
```json
{ "conditions":[
    {"dimension":"technical","field":"macd_golden_cross","operator":"EQ","value":true},
    {"dimension":"sentiment","field":"sentiment_index","operator":"GT","value":60} ],
  "logic":"AND", "page":1, "size":50 }
```
错误 `3002` 条件非法 / `3007` 维度暂不可用（V1 恒返回）。

### API24 评分卡（V3，契约先行）`GET /api/scores/{code}?tradeDate=`
V1 返回 `{"code":3008,"message":"评分卡V3开放"}`。

> API16-18（行情/资金/基本面）为 V2 端点，契约同分册 A 表格，落地时补充本文件。

---

## 6. M8 告警

### API21 创建规则 `POST /api/alert-rules`

```json
{ "ruleName": "茅台情绪反转",
  "scope": "SECURITY:600519",
  "dsl": { "conditions":[
      {"field":"sentiment_index_1h_drop","operator":"GT","value":40,"logic":"AND"} ],
    "description": "情绪指数1h跌幅>40%" },
  "cooldownSeconds": 900, "maxPerDay": 10, "dedupWindowSeconds": 900,
  "channels": ["wechat_work", "in_app"] }
```
**DSL 校验规则**（V1 白名单字段）：

| field | 维度 | operator | value |
|---|---|---|---|
| price_gte / price_lte | 价格 | 隐含 | 数值 |
| volume_ratio_surge | 声量 | 隐含 | 倍数(V1默认3) |
| sentiment_index_gt / _lt | 情绪 | 隐含 | 0-100 |
| sentiment_index_1h_drop | 情绪(V2) | GT | 百分比 |

错误：`3002` DSL 非法（含具体字段名）/ `3009` 规则数超限（≤50/用户）/ `3010` scope 非法。

### API22 事件历史 `GET /api/alert-events?startDate=&endDate=&status=&page=`
→ `[{id, ruleId, ruleName, securityCode, eventType, eventData, dedupStatus, pushStatus, triggeredAt, pushedAt}]`

### API23 启停 `PATCH /api/alert-rules/{id}` body `{status:0|1}` → `{id, status}`

---

## 7. WebSocket 协议细化

**连接**：`wss://host/ws`，STOMP 1.2，CONNECT 头带 `Authorization: Bearer <AT>`；AT 过期不断连，但服务端在下次推送前校验，过期则推送 `{type:"auth_expired"}` 并允许 60s 内 SEND 新 Token 续期。

**V1 开通频道**（其余 V2）：

| 频道 | 消息体 | 频率 |
|---|---|---|
| `/user/queue/quote` | `{msgId, securityCode, price, changePercent, volume, timestamp}` | 3-5s（自选股全量打包一帧） |
| `/user/queue/alert` | 同分册 A §2.3.3 告警格式 | 事件触发 |

**可靠性**：每条消息带单调递增 `msgId`；前端发现跳号 >3 次则触发重连。服务端 per-user 发送队列 100 条，溢出推送 `{type:"overflow", dropped:N}`。

**降级**：WS 不可用时前端自动降级为 10s 轮询 `GET /api/workbench`（仅取 watchlist+alerts 两个卡片字段）。

---

## 8. 版本与兼容策略

- 破坏性变更走 `/api/v2/...` 新路径；旧路径保留 ≥ 2 个版本周期
- 响应**只增不删**字段；废弃字段标注 `deprecated` 并保留 2 个版本
- 每端点在 OpenAPI（springdoc）自动生成文档，`/swagger-ui` 仅内网可达
