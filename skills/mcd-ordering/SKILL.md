---
name: mcd-ordering
description: 处理麦当劳中国的麦乐送、到店自取、得来速点餐及正在处理的点餐订单：选门店、选餐、用券、算价、下单、支付后查单和取消。用户提出麦当劳点餐或继续、修改点餐任务时使用；MCP 配置讲解、积分抽奖和主题活动预约不属于此技能。
aliases: [麦当劳, 巨无霸, 汉堡, 薯条, 门店, 套餐, 优惠券, 麦乐送]
---

# 麦当劳点餐

用当前可用的麦当劳 MCP 工具自主完成用户的实际请求。步骤由你根据目标和上下文自行规划：已知信息直接使用，缺失的关键信息（取餐方式、地址或门店、时间）向用户补问，其余自行判断。

## 红线（必须遵守）

- **不编造**：`storeCode`、`beCode`、`productCode`、`takeWayCode`、订单号等标识符只能使用工具返回的原始值，不能凭名称构造；服务未启用、请求失败或字段缺失时如实说明阻碍，不编造业务结果。
- **不越权**：用户只想看菜单、算价或查订单时，完成查询即停止，不扩展为下单；「只测试、不要下单」等表述不构成创建订单的授权。
- **不擅自花用户的钱或权益**：不能为了省钱自行领券、花积分或购买会员卡；这些操作只在用户明确要求时进行。
- **下单前给摘要**：调用 `create-order` 前，向用户说明门店、取餐方式、时间、商品与数量、用券、各项费用及应付总额，摘要必须与即将提交的参数一致。
- **不复用旧批准**：任何改变最终订单的修改（门店、地址、时间、商品、券、收费项）都需要重新算价、重新展示摘要并重新进入审核。
- **不重复下单**：`create-order` 超时或返回不完整时，先用 `query-order` 或 `order-list` 核查，确认未创建前不重发；结果不明时如实告知并停止重试。
- **结果不一致时停止自动操作**：报价与实际结果不一致时向用户说明并先核实订单，不自行补单、取消、退款或重新付款。

## 工具速查

- 时间：`now-time-info`（点餐开始时获取，用于落实「明天中午」等相对时间）
- 地址与门店：`delivery-query-addresses`、`delivery-query-stores`（麦乐送）；`query-nearby-stores`（自取/得来速）
- 菜单与优惠：`query-meals`、`query-meal-detail`、`query-store-coupons`、`query-my-coupons`、`available-coupons`
- 算价与下单：`calculate-price`（正式报价以它为准，菜单标价不等于应付总额）、`create-order`（取餐方式编码取自算价结果的 `takeWayList`）
- 订单：`query-order`、`order-list`、`cancel-order`
- 其他：`delivery-create-address`、`auto-bind-coupons`、`mall-*`（积分商城）、`party-order-create` 与 `query-party-*`（团餐）、`list-nutrition-foods`（营养）

工具的 inputSchema、description 和实际返回值优先于本文件的描述。只读查询会直接执行；写操作（下单、取消、领券、新增地址、积分兑换、团餐、抽奖）会进入审核流程，通过后才会真正执行。

## 特殊场景

新增地址、领券、积分兑换、营养目标、团餐、取消订单、错误恢复等场景的详细参数参考，按需读取 [references/branches.md](references/branches.md) 的相关部分。

维护参考：[麦当劳官方工具说明](https://github.com/M-China/mcd-mcp-server#3-工具列表)、[官方接口文档](https://open.mcd.cn/mcp/doc)。
