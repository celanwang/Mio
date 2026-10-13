# 首次配置与授权

<a id="quick-start-main"></a>

调用 `tencent_ride` 的 `preflight`，读取 `output.next_actions`。本步骤只做 Python 和 Token 的本机检查，常住城市不影响就绪；不调用打车接口，也不会校验 Token 在腾讯端是否仍有效。

- 缺少 Python：说明后端需安装 Python 3.8 或更新版本，并用 `TENCENT_RIDE_PYTHON` 指定解释器路径。Windows 可使用 python.exe，不依赖 python3 别名。
- 缺少 Token：引导用户在微信「我 → 服务 → 出行服务 → 我的 → 头像/昵称 → Token 信息」取得用户 Token，然后配置后端环境变量 `TENCENT_RIDE_TOKEN`，或本机不提交的 application.yml 中的 `app.tencent-ride.token`，重启应用。不要让用户在聊天里发送 Token；没有 save-token/delete-token 工具。
- 常住城市可留空：不要要求先配置，也不要自动保存本次出行城市。进入打车对话后主动询问本次城市、具体上车地点，再确认目的地；已知信息不重复询问。`poi-search` 必须传本次已确认的 `city_name`。
- 用户明确要求保存常住城市时，使用已确认的完整城市名，通过 `set-resident-city` 保存（需本次确认）；也可设置 `TENCENT_RIDE_CITY` 或 `app.tencent-ride.resident-city` 后重启。常住城市不能当作当前定位。

补齐必要项后重新 preflight；就绪后主动引导刚才的打车请求，不让用户重新提交。`resident_city_optional=true` 表示城市偏好可不设置，`resident_city_present=false` 不是配置错误。仍未就绪则说明缺项，不无限循环。Token 失效时需要用户在后端更新并重启；退出登录通过删除本机配置或环境变量并重启实现，不声称已在腾讯端撤销授权。
