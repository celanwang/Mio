# PersonalAgent 工具接入

本项目通过 `tencent_ride` 查询及维护行程，通过 `tencent_ride_create_order` 创建即时订单。不要运行 shell，不直接读写文件；官方 CLI 示例只是参数参考。查询工具输入结构：

```json
{"command":"poi-search","parameters":{"keyword":"深圳北站","city_name":"深圳市","scene":2}}
```

`parameters` 省略时为 `{}`。CLI 的连字符选项转为下划线参数名，去掉 `--`；JSON 参数直接使用对象或数组，不再编码成 JSON 字符串。所有文件路径由后端指定，不支持 `state_file`、`env_file`、`config_file` 等路径参数。

| command | parameters |
|---|---|
| preflight | 无；检查 Python 和 Token，不请求外部业务接口，常住城市可留空 |
| get-resident-city | 无 |
| set-resident-city | city_name（完整城市名，保存前确认） |
| poi-search | keyword、city_name 必填；city_name 使用本次已确认城市；page_size、page_index 可选；scene 为 0 普通、1 上车、2 下车 |
| select-poi | poiid、scene 必填，建议同时传最近搜索返回的 candidate_id；同一 POI 有多个推荐点时 candidate_id 必填 |
| estimate-price | 无，起终点从当前聊天草稿读取 |
| query-ongoing-order | 无，核对账号当前订单 |
| query-order | 无，查询账号进行中的订单详情 |
| query-driver-location | 无，查询当前订单司机位置 |
| check-cancel-fee | reason 可选，只查取消费，不取消 |
| cancel-order | orderId（字符串）、amountFen（分，整数）必填；reason 可选 |
| addr-keys | 无 |
| addr-get-value | key |
| addr-upsert-value | key、value_json（地址对象，结构见 addr-schema.md） |
| addr-sync-to-state | key、scene（1 上车、2 下车） |
| short-cut-keys | 无 |
| short-cut-get-value | key |
| short-cut-upsert-value | key、value_json（快捷场景对象） |
| delete-state | 无，用户确认后清空本聊天草稿，不删除订单或账号记录 |
| build-static-map-url | markers_json（坐标标记数组）；只生成链接 |

`query-order` 与 `query-ongoing-order` 查询的是当前进行中的订单，不提供历史订单列表；取消后可能没有当前订单。用户要查看历史订单时，说明当前工具不支持，并引导到腾讯出行服务小程序查看，不要将当前订单当作历史订单返回。

## 下单参数

用户只说“我要打车”时先询问本次城市与具体上车点，起点确定后再询问缺失的目的地；已知信息不重复追问。城市不保存为偏好也能搜索和询价。不从常住城市、服务器地址或旧行程推测当前位置，不需要先清空草稿才能询问。

`estimate-price` 以及其他草稿操作返回的顶层 `stateVersion` 绑定当前草稿。下单只用 `tencent_ride_create_order`，直接传以下顶层字段，不再传 `command` 或 `parameters`；通用工具不接受 `create-order`。必须使用本次有效询价的版本、真实车型键及完整摘要：

```json
{
  "price_estimate_keys":["询价返回的真实键"],
  "stateVersion":"询价返回的真实状态版本",
  "summary":"从已确认上车点到目的地，已选车型及供应商，预估价格，乘客偏好"
}
```

修改起终点后重新询价。不能填写示例键或自己构造报价。后端会检查版本及所选车型，并核对账号是否已有订单；当前调用仍需用户批准。

`poi-search` 的 `scene=1/2` 每次只保留本次搜索（本页）的候选，搜索开始就清空该端旧候选并撤销旧报价；失败或零结果也不能复用旧行程。搜索只产生候选，只有一个结果也必须 `select-poi` 后才能询价。`scene=0` 只是普通搜索，不修改行程。

同一 `poiid` 可对应多个推荐上下车点，项目为每个点提供 `candidate_id`，不能只按 POI 去重或选择。示例：`{"command":"select-poi","parameters":{"poiid":"实际 POI 标识","candidate_id":"本次搜索返回的候选标识","scene":2}}`。成功时 `output.selected` 返回真正保存的地点；核对 `name`、`address`、`point_name` 及两组坐标。实际推荐点与指定出入口不一致时先请用户确认。旧版本草稿没有选择标记时需要重新选择，不能直接续用旧报价。

`user_prefer_labels` 可选，直接放在顶层，使用整数数组（1 接驾近、2 接驾快、3 电车、4 油车）；无偏好可省略。不传底层接口的 `estimateKey`、`productStr`、地址、坐标或预约时间。当前仅支持即时用车。用户要求最低价时同价可选接口顺序第一项，在摘要中明确选定的平台、车型和金额。

## 返回值

普通脚本结果包含 `exitCode`、`output`、`error` 和 `stateVersion`。`output` 是原脚本 JSON，实际业务结果通常位于 `output.result.body`。需要同时核对退出码、业务 code、字段完整性和订单号。`mode="error"` 时按 message 说明失败，不重试写入。

项目版 `preflight` 的 `output.resident_city_optional=true`，城市偏好留空仍可返回 `next_actions=["ready"]`。地点搜索前用对话补齐本次城市，不能转而要求用户填配置文件。

询价成功后展示 `output.result.body.data.distance`（米）、`estimateTime`（秒）和车型报价 `estimatePrice`（分），转为公里、分钟和元。缺失值不计算或编造；询价本身不会下单。当前命令没有支付或付款二维码能力，下单成功后按真实状态引导到腾讯出行服务小程序处理支付。

询价顶层 `trip.pickup` / `trip.dropoff` 返回实际使用的 POI、地址、推荐点和坐标；`units` 标明原始单位，`quotedAt` 为成功报价的 Unix 秒时间戳。回复和下单摘要必须对应这条实际路线，不把用户输入的新地名替换到旧报价。`estimatePrice` 原样分转元，优惠信息单列，不自行再次打折或反算原价。与小程序不一致时先核对实际路线、车型和报价时间，没有证据不解释为路况或优惠差异。

`check-cancel-fee` 返回顶层 `mode="cancellation_fee"`、`orderId`、`amountFen`、`amountYuan`。确认取消时把该订单和费用原样放入 parameters，summary 展示订单、取消原因及费用。后端再次核对订单和费用；`fee_changed` 表示尚未取消，需要按新费用重新确认。

不提供通过聊天保存或删除 Token 的工具。Token 从后端 `TENCENT_RIDE_TOKEN` 或 `app.tencent-ride.token` 取得；常住城市可选，仅在用户要求保存偏好时通过配置或专用工具设置。Python 至少 3.8（项目适配层要求）。官方参考文档中的用户目录路径不适用于本项目。
