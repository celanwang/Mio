"""PersonalAgent adapter. Official scripts remain unmodified; no shell or live calls on import."""
import contextlib
import hashlib
import io
import json
import os
import re
import tempfile
from pathlib import Path
import sys
import time

import tms_takecar as tms
import tms_takecar_api as api

_upstream_estimate_transform = tms.transform_estimate_price_result
_upstream_ongoing_data = tms.extract_query_ongoing_data


def extract_ongoing_data(body):
    # The live API also represents "no ongoing order" as two successful envelopes + explicit null.
    # Missing data or any unsuccessful/unknown business status must still fail closed.
    if isinstance(body, dict) and body.get("code") in (0, "0"):
        inner = body.get("data")
        if isinstance(inner, dict) and "code" in inner:
            if inner.get("code") not in (0, "0"):
                return None
            if "data" in inner and inner["data"] is None:
                return {"hasOnGoingOrder": False}
    return _upstream_ongoing_data(body)


def transform_estimate_result(result):
    # Preserve gateway/business errors which the upstream field filter would replace with nulls.
    body = result.get("body") if isinstance(result, dict) else None
    if not isinstance(body, dict) or body.get("code") not in (0, "0"):
        return result
    return _upstream_estimate_transform(result)


def has_quote_products(products):
    if not isinstance(products, list):
        return False
    for product in products:
        prices = product.get("priceEstimate") if isinstance(product, dict) else None
        if isinstance(prices, list) and any(isinstance(price, dict) and price.get("priceEstimateKey")
                                            for price in prices):
            return True
    return False


def invalidate_quote(session, scene=None):
    state = tms.load_state_file()
    if scene in (1, 2):
        state[tms._get_scene_key(scene)] = []
    state["estimate"] = None
    state["userPreferLabels"] = []
    tms.save_state_file(state)
    (session / "quote.json").unlink(missing_ok=True)


def update_search_candidates(scene, items, state_file=None):
    # One POI can have several real boarding/alighting points. Never deduplicate by POI alone.
    if scene not in (1, 2):
        return None
    candidates = {}
    for item in items:
        if not isinstance(item, dict) or not item.get("poiid"):
            continue
        candidate = tms._normalize_poi_item(item)
        identity = json.dumps(candidate, sort_keys=True, ensure_ascii=False)
        candidate["candidate_id"] = hashlib.sha256(identity.encode("utf-8")).hexdigest()
        candidate["selected"] = False
        candidates[candidate["candidate_id"]] = candidate
    items[:] = candidates.values()
    state = tms.load_state_file(state_file)
    state[tms._get_scene_key(scene)] = items
    state["estimate"] = None
    state["userPreferLabels"] = []
    return tms.save_state_file(state, state_file)


def selected_trip():
    state = tms.load_state_file()
    trip = {}
    for scene, key, label in ((1, "pickup", "上车点"), (2, "dropoff", "下车点")):
        try:
            item = tms._require_selected_scene_item(state, scene)
        except ValueError:
            raise ValueError(f"缺少已确认的{label}，请先搜索并 select-poi 选择实际地点。") from None
        if item.get("selected") is not True:
            raise ValueError(f"请先 select-poi 确认实际{label}及推荐上下车点，再询价；不能只修改回复中的地名。")
        trip[key] = dict(item)
    return trip


def select_candidate(parameters, session):
    # Validate the original CLI fields locally; candidate_id is a project adapter field.
    cli_arguments("select-poi", {k: v for k, v in parameters.items() if k != "candidate_id"})
    scene = int(parameters["scene"])
    state = tms.load_state_file()
    key = tms._get_scene_key(scene)
    matches = [item for item in state[key] if isinstance(item, dict)
               and str(item.get("poiid", "")) == str(parameters["poiid"]).strip()]
    if "candidate_id" in parameters:
        matches = [item for item in matches if item.get("candidate_id") == parameters["candidate_id"]]
    if len(matches) != 1:
        raise ValueError("地点不存在或同一 POI 有多个上下车点，请从最近一次搜索结果选取准确的 candidate_id。")
    selected = {**matches[0], "selected": True}
    state[key] = [selected]
    state["estimate"] = None
    state["userPreferLabels"] = []
    state["orderId"] = None
    tms.save_state_file(state)
    (session / "quote.json").unlink(missing_ok=True)
    return {"exitCode": 0, "output": {"updated": True, "scene": scene, "selected": selected},
            "error": None, "stateVersion": state_version()}


# Values are positional argument names or supported CLI option names, never caller-selected paths.
COMMANDS = {
    "preflight": (), "get-resident-city": (), "set-resident-city": ("city_name",),
    "delete-state": (), "addr-keys": (), "addr-get-value": ("key",),
    "addr-upsert-value": ("key", "value_json"), "addr-sync-to-state": ("key", "scene"),
    "short-cut-keys": (), "short-cut-get-value": ("key",),
    "short-cut-upsert-value": ("key", "value_json"),
    "poi-search": ("keyword", "city_name", "page_size", "page_index", "scene"),
    "select-poi": ("poiid", "scene", "candidate_id"), "estimate-price": (),
    "create-order": ("price_estimate_keys", "user_prefer_labels"),
    "query-ongoing-order": (), "query-order": (), "query-driver-location": (),
    "check-cancel-fee": ("reason",), "cancel-order": ("orderId", "amountFen", "reason"),
    "build-static-map-url": ("markers_json",),
}
POSITIONAL = {"set-resident-city": ("city_name",), "addr-get-value": ("key",),
              "addr-upsert-value": ("key", "value_json"), "addr-sync-to-state": ("key",),
              "short-cut-get-value": ("key",), "short-cut-upsert-value": ("key", "value_json")}


def initialize(request):
    session = Path(request["sessionDirectory"])
    account = Path(request["accountDirectory"])
    session.mkdir(parents=True, exist_ok=True)
    account.mkdir(parents=True, exist_ok=True)
    tms.TOKEN_CONFIG_DIR = str(account)
    tms.TOKEN_CONFIG_FILE = str(account / "unused-token")
    tms.ENV_CONFIG_FILE = str(account / "city.json")
    tms.STATE_FILE_PATH = str(session / "state.json")
    tms.ADDR_FILE_PATH = str(account / "addr.json")
    tms.SHORT_CUT_FILE_PATH = str(account / "short-cut.json")
    tms.PREFERENCE_FILE_PATH = str(account / "preference.md")
    tms.DEFAULT_HTTP_TIMEOUT_SECONDS = 20.0
    api.DEFAULT_HTTP_TIMEOUT_SECONDS = 20.0
    tms.transform_estimate_price_result = transform_estimate_result
    tms.extract_query_ongoing_data = extract_ongoing_data
    tms.update_state_from_poi_search = update_search_candidates
    token = str(request.get("token", "")).strip()

    def load_config(env_file=None):
        config = tms.build_default_env_config()
        try:
            config["resident_city"] = json.loads(Path(tms.ENV_CONFIG_FILE).read_text(encoding="utf-8"))["resident_city"]
        except (OSError, ValueError, KeyError):
            config["resident_city"] = str(request.get("residentCity", "")).strip()
        config["token"] = token
        return config

    def save_config(config, env_file=None):
        # Upstream preflight saves its configuration. Persist only the city, never the Token.
        Path(tms.ENV_CONFIG_FILE).write_text(json.dumps({"resident_city": config.get("resident_city", "")},
                                                     ensure_ascii=False), encoding="utf-8")
        return tms.ENV_CONFIG_FILE

    tms.load_env_config = load_config
    tms.save_env_config = save_config
    tms.load_token = lambda environ=None: token
    return session, account


def state_version():
    state = tms.load_state_file()
    identity = hashlib.sha256(tms.load_token().encode("utf-8")).hexdigest()
    return hashlib.sha256((identity + json.dumps(state, sort_keys=True, ensure_ascii=False)).encode("utf-8")).hexdigest()


def cli_arguments(command, parameters):
    if command not in COMMANDS or not isinstance(parameters, dict):
        raise ValueError("Unsupported command or parameters")
    if set(parameters) - set(COMMANDS[command]):
        raise ValueError("Unsupported parameters; file paths, shell options and Tokens are not accepted")
    if any(isinstance(v, str) and v.startswith("-") for v in parameters.values()):
        raise ValueError("Parameter values cannot be CLI options")
    result = [command]
    for key in POSITIONAL.get(command, ()):
        if key not in parameters:
            raise ValueError("Missing parameter: " + key)
        value = parameters[key]
        result.append(json.dumps(value, ensure_ascii=False) if not isinstance(value, str) else value)
    for key, value in parameters.items():
        if key in POSITIONAL.get(command, ()):
            continue
        result.extend(["--" + key.replace("_", "-"),
                       json.dumps(value, ensure_ascii=False) if not isinstance(value, str) else value])
    # No abbreviation of flags; no access to arbitrary files through CLI arguments.
    parser = tms.build_parser()
    for action in parser._actions:
        if hasattr(action, "choices") and isinstance(action.choices, dict):
            for child in action.choices.values():
                child.allow_abbrev = False
    with contextlib.redirect_stderr(io.StringIO()):
        try:
            parser.parse_args(result)
        except SystemExit:
            raise ValueError("Missing or invalid command parameters") from None
    return result


def decode_output(value):
    if not value.strip():
        return None
    try:
        return json.loads(value)
    except ValueError:
        return value.strip()


def run_cli(arguments):
    stdout, stderr = io.StringIO(), io.StringIO()
    code = tms.main(arguments, stdout=stdout, stderr=stderr)
    return {"exitCode": code, "output": decode_output(stdout.getvalue()),
            "error": decode_output(stderr.getvalue())}


def current_order():
    token = tms.load_token()
    if not token:
        raise ValueError("Token is not configured; configure TENCENT_RIDE_TOKEN on the backend")
    result = api.post_request(tms.DEFAULT_API_BASE, tms.DEFAULT_QUERY_ONGOING_ORDER_PATH,
                              tms.build_headers(token), {}, token=token, timeout_seconds=20)
    body = result.get("body")
    if not isinstance(body, dict) or body.get("code") not in (0, "0") or api.is_invalid_token_error(body):
        raise ValueError("Unable to verify current order; stop and check account authorization")
    data = tms.extract_query_ongoing_data(body)
    if not isinstance(data, dict):
        raise ValueError("Current order response is incomplete")
    if data.get("hasOnGoingOrder") is False:
        return None
    order_id = data.get("orderId")
    if not isinstance(order_id, str) or not order_id.strip() or order_id == "null":
        raise ValueError("Current order response has no valid orderId")
    return order_id.strip()


def cancellation_fee(parameters):
    order_id = current_order()
    if not order_id:
        raise ValueError("No ongoing order")
    expected = parameters.get("orderId")
    if expected is not None and expected != order_id:
        raise ValueError("The ongoing order changed; query again and request a new confirmation")
    stderr = io.StringIO()
    result, error = tms.post_cancel_order_payload({}, tms.build_cancel_order_payload(
        order_id, False, parameters.get("reason", "")), stderr)
    if error is not None:
        raise ValueError("Failed to check cancellation fee; do not cancel")
    body = result.get("body")
    if not isinstance(body, dict) or body.get("code") not in (0, "0"):
        raise ValueError("Cancellation fee query was not successful")
    data = body.get("data")
    if not isinstance(data, dict) or not isinstance(data.get("waiverFee"), bool):
        raise ValueError("Cancellation fee response is incomplete")
    amount = 0 if data["waiverFee"] else data.get("amount")
    if isinstance(amount, bool) or not isinstance(amount, int) or amount < 0:
        raise ValueError("Invalid cancellation amount")
    return {"orderId": order_id, "amountFen": amount, "amountYuan": amount / 100}


def write_operation_receipt(account, logical_id, scope_key, result, token):
    if logical_id is None:
        return
    if not isinstance(logical_id, str) or not re.fullmatch(r"[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}", logical_id):
        raise ValueError("Invalid logical operation id")
    if not isinstance(scope_key, str) or not re.fullmatch(r"[a-f0-9]{64}", scope_key):
        raise ValueError("Invalid operation scope")
    directory = account / "operations"
    directory.mkdir(mode=0o700, exist_ok=True)
    target = directory / (logical_id + ".json")
    data = {"logicalId": logical_id, "scopeKey": scope_key, "receipt": redact(result, token)}
    fd, temporary = tempfile.mkstemp(prefix="receipt-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        # Never overwrite an existing logical operation with a different invocation.
        os.link(temporary, target)
        directory_fd = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        os.unlink(temporary)


def execute(request):
    command = request.get("command")
    parameters = request.get("parameters", {})
    if command not in COMMANDS or not isinstance(parameters, dict):
        raise ValueError("Unsupported command or parameters")
    extra = set(parameters) - set(COMMANDS[command])
    if extra:
        allowed = ", ".join(COMMANDS[command]) or "{}"
        raise ValueError(f"Unsupported parameters for {command}: {', '.join(sorted(extra))}. Allowed: {allowed}. "
                         "stateVersion and summary must be top-level fields; use tencent_ride_create_order for bookings.")
    if command == "poi-search":
        city = parameters.get("city_name")
        if not isinstance(city, str) or not city.strip():
            raise ValueError("请先询问用户本次出行的城市及具体地点，再使用 city_name 搜索；不能默认使用常住城市。")
    if sys.version_info < (3, 8):
        return {"exitCode": 1, "output": {"python_ok": False, "next_actions": ["install_python"]},
                "error": "PersonalAgent adapter requires Python 3.8 or newer"}
    session, account = initialize(request)
    if command == "create-order":
        logical_id = request.get("logicalOperationId")
        if logical_id is not None:
            if not isinstance(logical_id, str) or not re.fullmatch(r"[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}", logical_id):
                raise ValueError("Invalid logical operation id")
            if (account / "operations" / (logical_id + ".json")).exists():
                raise ValueError("Operation already has a receipt; reconcile instead of submitting again")
            if not isinstance(request.get("operationScope"), str) or not re.fullmatch(r"[a-f0-9]{64}", request["operationScope"]):
                raise ValueError("Invalid operation scope")
    marker = account / "order-attempt.json"
    quote_file = session / "quote.json"
    if command == "select-poi":
        return select_candidate(parameters, session)
    if command in ("check-cancel-fee", "cancel-order"):
        fee = cancellation_fee(parameters)
        if command == "check-cancel-fee":
            return {"mode": "cancellation_fee", **fee}
        if not request.get("summary") or parameters.get("orderId") != fee["orderId"]:
            raise ValueError("Cancellation requires the approved orderId and summary")
        if isinstance(parameters.get("amountFen"), bool) or parameters.get("amountFen") != fee["amountFen"]:
            return {"mode": "fee_changed", **fee,
                    "message": "取消费用变化，请展示新费用并重新确认。尚未取消。"}
        result = api.post_request(tms.DEFAULT_API_BASE, tms.DEFAULT_CANCEL_ORDER_PATH,
                                  tms.build_headers(tms.load_token()), tms.build_cancel_order_payload(
                                      fee["orderId"], True, parameters.get("reason", "")),
                                  token=tms.load_token(), timeout_seconds=20)
        return {"mode": "cancel_result", "result": result}

    arguments = cli_arguments(command, parameters)
    if command == "poi-search" and int(parameters.get("scene", 0)) in (1, 2):
        # Clear even on empty/error results: a corrected location cannot silently use the old trip.
        invalidate_quote(session, int(parameters["scene"]))
    elif command in ("estimate-price", "addr-sync-to-state"):
        invalidate_quote(session)
    if command in ("estimate-price", "create-order"):
        trip = selected_trip()
    if command == "preflight":
        # The project asks about this trip in conversation; a saved home city is optional.
        output = tms.build_preflight_result()
        output["resident_city_optional"] = True
        actions = [action for action in output["next_actions"] if action != "setup_resident_city"]
        output["next_actions"] = actions or ["ready"]
        return {"exitCode": 0 if output["next_actions"] == ["ready"] else 1,
                "output": output, "error": None, "stateVersion": state_version()}
    if command == "create-order":
        if not request.get("summary") or request.get("stateVersion") != state_version():
            raise ValueError("报价或行程已改变，请重新询价、展示摘要并确认下单。")
        quote = json.loads(quote_file.read_text(encoding="utf-8")) if quote_file.exists() else {}
        if quote.get("stateVersion") != state_version() or time.time() - quote.get("time", 0) > 300:
            raise ValueError("缺少有效询价或报价已超过 5 分钟，请重新询价并确认。")
        keys = parameters.get("price_estimate_keys")
        state = tms.load_state_file()
        products = (state.get("estimate") or {}).get("products", [])
        available = {p.get("priceEstimateKey") for p in products if isinstance(p, dict)}
        if not isinstance(keys, list) or not keys or any(not isinstance(k, str) or k not in available for k in keys):
            raise ValueError("Choose valid price_estimate_keys from the current estimate")
        if marker.exists():
            raise ValueError("已有下单记录或上次结果不明，请先 query-ongoing-order 核对，不重复下单。")
        if current_order():
            raise ValueError("账号已有进行中的订单，请先查询该订单。")
        # Check all local payload invariants before marking a potentially submitted order.
        for product in products:
            if isinstance(product, dict):
                product["userSelect"] = int(product.get("priceEstimateKey") in keys)
        tms.build_create_order_payload_from_state(state)
        marker.write_text(json.dumps({"status": "unknown", "time": time.time()}), encoding="utf-8")

    result = run_cli(arguments)
    if command == "addr-sync-to-state" and result["exitCode"] == 0:
        state = tms.load_state_file()
        key = tms._get_scene_key(int(parameters["scene"]))
        state[key][0]["selected"] = True
        tms.save_state_file(state)
        result["output"]["selected"] = dict(state[key][0])
    if command == "estimate-price":
        result["trip"] = trip
        result["units"] = {"distance": "m", "estimateTime": "s", "estimatePrice": "fen"}
        output = result.get("output")
        body = ((output.get("result") or {}).get("body")) if isinstance(output, dict) else None
        data = body.get("data") if isinstance(body, dict) else None
        products = data.get("product") if isinstance(data, dict) else None
        if (result["exitCode"] == 0 and isinstance(body, dict) and body.get("code") in (0, "0")
                and not body.get("error") and isinstance(data, dict) and data.get("estimateKey") and has_quote_products(products)):
            result["quotedAt"] = time.time()
            quote_file.write_text(json.dumps({"stateVersion": state_version(), "time": result["quotedAt"]}), encoding="utf-8")
        else:
            invalidate_quote(session)
            if result["exitCode"] == 0:
                result["exitCode"] = 5
            result["retryable"] = False
            if isinstance(output, dict):
                output["mode"] = "quote_unavailable"
            if not result.get("error"):
                error = body.get("error") if isinstance(body, dict) else None
                details = error if isinstance(error, dict) else body if isinstance(body, dict) else {}
                message = details.get("message") or details.get("msg") or details.get("errMsg")
                result["error"] = {"message": message or "腾讯询价未返回有效报价，请稍后重试或到小程序核对。",
                                   "action": "停止本轮询价，不自动重复请求，不下单；等待用户决定。"}
    if command == "create-order":
        body = ((result.get("output") or {}).get("result") or {}).get("body")
        if isinstance(body, dict) and body.get("code") in (0, "0") and (body.get("data") or {}).get("orderId"):
            marker.write_text(json.dumps({"status": "observed", "orderId": body["data"]["orderId"]}), encoding="utf-8")
        elif isinstance(body, dict) and body.get("code") is not None and body.get("code") not in (0, "0"):
            marker.unlink(missing_ok=True)
        if not (isinstance(body, dict) and body.get("code") in (0, "0") and (body.get("data") or {}).get("orderId")):
            result["retryable"] = False
    elif command == "query-ongoing-order" and result["exitCode"] == 0:
        body = ((result.get("output") or {}).get("result") or {}).get("body")
        data = tms.extract_query_ongoing_data(body) if isinstance(body, dict) and body.get("code") in (0, "0") else None
        if isinstance(data, dict) and marker.exists():
            previous = json.loads(marker.read_text(encoding="utf-8"))
            if data.get("orderId") and data.get("hasOnGoingOrder") is not False:
                marker.write_text(json.dumps({"status": "observed", "orderId": data["orderId"]}), encoding="utf-8")
            elif data.get("hasOnGoingOrder") is False and previous.get("status") == "observed":
                marker.unlink()
    result["stateVersion"] = state_version()
    if command == "create-order":
        try:
            write_operation_receipt(account, request.get("logicalOperationId"), request.get("operationScope"), result, tms.load_token())
        except OSError:
            raise ValueError("Correlated receipt could not be persisted; reconcile manually, never resubmit") from None
    return result


def redact(value, token):
    if isinstance(value, dict):
        return {key: ("[REDACTED]" if key.lower() in ("token", "authorization") else redact(item, token))
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item, token) for item in value]
    return value.replace(token, "[REDACTED]") if isinstance(value, str) and token else value


def main():
    request = {}
    try:
        request = json.load(sys.stdin)
        result = execute(request)
    except (Exception, SystemExit) as failure:
        result = {"mode": "error", "message": str(failure),
                  "hint": "若提交操作后失败，先查订单，不自动重试。"}
        if request.get("command") in ("create-order", "cancel-order"):
            result["retryable"] = False
    print(json.dumps(redact(result, str(request.get("token", ""))), ensure_ascii=False))


if __name__ == "__main__":
    main()
