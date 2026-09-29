#!/usr/bin/env python3
"""使用固定卡顿数据验证本机 ClickHouse 的表、去重和查询统计。"""

import argparse
import base64
import hashlib
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path


# 脚本资源均相对仓库根目录定位。
ROOT = Path(__file__).resolve().parents[1]
SCHEMA_ROOT = ROOT / "backend/src/main/resources/db/clickhouse"
FIXTURE = ROOT / "backend/src/test/resources/fixtures/jank-dataset.json"


def read_local_env():
    """读取被 Git 忽略的本地配置，不将凭据打印到终端。"""
    path = ROOT / ".env.local"
    if not path.is_file():
        raise ValueError("缺少 .env.local。")
    values = {}
    for line in path.read_text().splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        name, value = line.split("=", 1)
        values[name.strip()] = value.strip().strip("\"'")
    return values


class ClickHouse:
    """通过 HTTP 接口执行 SQL，并将错误完整返回给调用者。"""

    def __init__(self, url, database, user, password):
        self.url = url.rstrip("/") + "/?" + urllib.parse.urlencode({"database": database})
        self.authorization = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()

    def execute(self, sql, body=""):
        payload = (sql + ("\n" + body if body else "")).encode()
        request = urllib.request.Request(self.url, data=payload, method="POST")
        request.add_header("Authorization", self.authorization)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read().decode()
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"ClickHouse HTTP {error.code}: {error.read().decode()}") from error

    def rows(self, sql):
        return [json.loads(line) for line in self.execute(sql).splitlines() if line]

    def insert(self, table, rows):
        if not rows:
            return
        body = "\n".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in rows) + "\n"
        self.execute(f"INSERT INTO apm.{table} FORMAT JSONEachRow", body)
        print(f"写入 {table}: {len(rows)} 行")


def assert_equal(actual, expected, message):
    """固定数据集的断言携带实际值，便于排查统计差异。"""
    if actual != expected:
        raise AssertionError(f"{message}：预期 {expected}，实际 {actual}")


def normalize_fingerprint(value):
    """按服务端历史固定数据算法归一化堆栈和场景文本。"""
    value = re.sub(r"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>", value or "")
    value = re.sub(r"(?i)0x[0-9a-f]+", "<address>", value)
    value = re.sub(r"\b\d+(?:\.\d+)?\b", "<number>", value)
    return re.sub(r"\s+", " ", value).strip().lower()


def analyze_jank(payload):
    """根据采样区间生成卡顿覆盖时长、时间片和调用树。"""
    duration = int(payload["messageDurationNs"])
    interval = int(payload["samplingIntervalNs"])
    samples = sorted(payload["samples"], key=lambda sample: int(sample["offsetNs"]))
    slices = []
    weights = defaultdict(int)
    cursor = covered = 0
    for index, sample in enumerate(samples):
        start = max(0, int(sample["offsetNs"]))
        if start > cursor:
            slices.append(dict(startOffsetNs=cursor, endOffsetNs=start, stackId=None, covered=False))
        next_start = max(start, int(samples[index + 1]["offsetNs"])) if index + 1 < len(samples) else duration
        end = min(duration, next_start)
        if index + 1 >= len(samples) or next_start - start > interval * 2:
            end = min(duration, start + interval)
        if end > start:
            slices.append(dict(startOffsetNs=start, endOffsetNs=end, stackId=sample["stackId"], covered=True))
            weights[sample["stackId"]] += end - start
            covered += end - start
            cursor = max(cursor, end)
        if index + 1 < len(samples):
            next_start = min(duration, next_start)
            if next_start > cursor:
                slices.append(dict(startOffsetNs=cursor, endOffsetNs=next_start, stackId=None, covered=False))
                cursor = next_start
    if cursor < duration:
        slices.append(dict(startOffsetNs=cursor, endOffsetNs=duration, stackId=None, covered=False))
    tree = []
    for stack_id, weight in weights.items():
        nodes = []
        for frame in reversed(payload["stackDictionary"].get(stack_id, [])):
            nodes = [dict(className=frame["className"], methodName=frame["methodName"],
                          estimatedDurationNs=weight, estimatedUnattributedDurationNs=0, children=nodes)]
        tree.extend(nodes)
    return dict(exactMessageDurationNs=duration, estimatedDurationNs=covered,
                estimatedUnattributedDurationNs=0, coveredDurationNs=covered,
                uncoveredDurationNs=max(0, duration - covered), sampleSlices=slices, callTree=tree,
                stackDictionary=payload["stackDictionary"], expectedSampleCount=payload["expectedSampleCount"],
                parsedSampleCount=payload["parsedSampleCount"], missingSampleCount=payload["missingSampleCount"],
                algorithmVersion=payload["algorithmVersion"], warnings=[]), weights


def fingerprint(app_id, package_name, payload, weights):
    """从覆盖时长最大的堆栈生成固定数据集 Issue 指纹。"""
    selected = sorted(weights, key=lambda key: (-weights[key], key))[:1]
    frames = payload["stackDictionary"].get(selected[0], []) if selected else []
    application_frames = [frame for frame in frames if frame.get("applicationFrame")][:8]
    if not application_frames:
        application_frames = frames[:8]
    path = ";".join(normalize_fingerprint(frame.get("className")) + "#" +
                    normalize_fingerprint(frame.get("methodName")) for frame in application_frames) or "missing"
    source = "|".join((app_id, package_name, normalize_fingerprint(payload["scene"]), path))
    return hashlib.sha256(source.encode()).hexdigest()


def compact(value):
    """将嵌套报告转换为数据库 JSON 字符串列。"""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def clickhouse_time(milliseconds):
    """将事件毫秒时间戳转换为 UTC DateTime64 文本。"""
    return datetime.fromtimestamp(int(milliseconds) / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


def initialize_schema(client):
    """按版本初始化测试 schema；004 迁移前拒绝清理非空业务表。"""
    tables = ("apm_event_raw", "apm_crash_detail", "apm_event_hourly", "apm_crash_issue_hourly",
              "apm_jank_event", "apm_jank_detail", "apm_jank_issue_hourly", "apm_frame_scene_summary",
              "apm_device_suspension_segment", "apm_device_suspension_daily")
    existing = {row["name"] for row in client.rows("SELECT name FROM system.tables WHERE database='apm' FORMAT JSONEachRow")}
    nonempty = [table for table in tables if table in existing and
                int(client.rows(f"SELECT count() AS n FROM apm.{table} FORMAT JSONEachRow")[0]["n"]) > 0]
    if nonempty:
        raise RuntimeError("拒绝执行 004：业务表非空，脚本不会删除数据：" + ", ".join(nonempty))
    identity = client.rows("SELECT count() AS n FROM system.columns WHERE database='apm' "
                           "AND table='apm_event_raw' AND name='app_id' AND type='UUID' FORMAT JSONEachRow")
    versions = (4, 5) if int(identity[0]["n"]) == 1 else (1, 2, 3, 4, 5)
    names = {1: "001_crash_schema.sql", 2: "002_jank_schema.sql", 3: "003_jank_sampling_quality.sql",
             4: "004_application_identity_schema.sql", 5: "005_memory_metrics.sql"}
    count = 0
    for version in versions:
        for statement in (part.strip() for part in (SCHEMA_ROOT / names[version]).read_text().split(";")):
            statement = "\n".join(line for line in statement.splitlines() if not line.lstrip().startswith("--")).strip()
            if statement:
                client.execute(statement + ";")
                count += 1
    print(f"schema 初始化完成：{count} 条语句")


def build_rows(app_id, events):
    """按原固定数据脚本生成五类 ClickHouse JSONEachRow 数据。"""
    output = defaultdict(list)
    # 固定数据原始时间已可能超过 30 天 TTL；平移至昨日并保持跨 UTC 天的间隔。
    first_day = datetime.fromtimestamp(min(event["occurredAt"] for event in events) / 1000, timezone.utc).date()
    day_offset = (datetime.now(timezone.utc).date() - timedelta(days=1) - first_day).days
    event_offset_ms = day_offset * 86400000
    received_base = datetime.now(timezone.utc).timestamp() * 1000
    for index, event in enumerate(events):
        event_type = event["eventType"]
        event_time = clickhouse_time(event["occurredAt"] + event_offset_ms)
        received_time = clickhouse_time(received_base + index)
        common = dict(app_id=app_id, event_id=event["eventId"], event_time=event_time,
                      received_time=received_time)
        identity = {"package_name": event["packageName"]}
        analysis = weights = issue = None
        if event_type == "jank":
            analysis, weights = analyze_jank(event["jank"])
            issue = fingerprint(app_id, event["packageName"], event["jank"], weights)
        payload = event.get("jank") or event.get("frameSceneSummary") or event.get("foregroundSuspensionSummary") or {}
        output["apm_event_raw"].append(dict(**common, **identity, event_type=event_type,
            schema_version=event["schemaVersion"], app_version=event["appVersion"],
            version_code=event["versionCode"], build_id=event["buildId"], channel=event["channel"],
            environment=event["environment"], session_id=event["sessionId"],
            anonymous_device_id=event["anonymousDeviceId"], os_version=event["osVersion"],
            device_model=event["deviceModel"], network_type="", duration_ms=None, status="",
            measurements={}, attributes={}, crash_kind="", crash_fatal=0, crash_exception_type="",
            crash_fingerprint=issue or "", fingerprint_version="jank-v1" if issue else "",
            symbolication_status=""))
        if event_type == "jank":
            output["apm_jank_event"].append(dict(**common, **identity, schema_version=event["schemaVersion"],
                session_id=event["sessionId"], anonymous_device_id=event["anonymousDeviceId"],
                app_version=event["appVersion"], version_code=event["versionCode"], build_id=event["buildId"],
                channel=event["channel"], environment=event["environment"], os_version=event["osVersion"],
                device_model=event["deviceModel"], network_type="", scene=payload["scene"],
                algorithm_version=payload["algorithmVersion"], message_duration_ns=payload["messageDurationNs"],
                threshold_ns=payload["thresholdNs"], sampling_interval_ns=payload["samplingIntervalNs"],
                estimated_duration_ns=analysis["estimatedDurationNs"],
                estimated_unattributed_duration_ns=analysis["estimatedUnattributedDurationNs"],
                covered_duration_ns=analysis["coveredDurationNs"], uncovered_duration_ns=analysis["uncoveredDurationNs"],
                expected_sample_count=payload["expectedSampleCount"], parsed_sample_count=payload["parsedSampleCount"],
                missing_sample_count=payload["missingSampleCount"], fingerprint=issue, fingerprint_version="jank-v1",
                jank_payload_json=compact(payload), jank_analysis_json=compact(analysis)))
            output["apm_jank_detail"].append(dict(**common, fingerprint=issue, fingerprint_version="jank-v1",
                stack_dictionary_json=compact(payload["stackDictionary"]), samples_json=compact(analysis["sampleSlices"]),
                call_tree_json=compact(analysis["callTree"]), evidence_json=compact({key: analysis[key] for key in
                ("exactMessageDurationNs", "estimatedDurationNs", "estimatedUnattributedDurationNs",
                 "coveredDurationNs", "uncoveredDurationNs", "algorithmVersion")})))
        elif event_type == "frame_scene_summary":
            frame = event["frameSceneSummary"]
            output["apm_frame_scene_summary"].append(dict(**common, session_id=event["sessionId"],
                anonymous_device_id=event["anonymousDeviceId"], app_version=event["appVersion"],
                channel=event["channel"], environment=event["environment"], os_version=event["osVersion"],
                device_model=event["deviceModel"], scene=frame["scene"], algorithm_version=frame["algorithmVersion"],
                active_duration_ms=frame["activeDurationMs"], ui_refresh_frame_count=frame["uiRefreshFrameCount"],
                refresh_rate_hz=frame["refreshRateHz"], normalized_fps60=frame["normalizedFps60"],
                frame_duration_histogram_json=compact(frame.get("frameDurationHistogram") or {})))
        elif event_type == "foreground_suspension_summary":
            suspension = event["foregroundSuspensionSummary"]
            output["apm_device_suspension_segment"].append(dict(**common, session_id=event["sessionId"],
                anonymous_device_id=event["anonymousDeviceId"], app_version=event["appVersion"],
                channel=event["channel"], environment=event["environment"], os_version=event["osVersion"],
                device_model=event["deviceModel"], algorithm_version=suspension["algorithmVersion"],
                foreground_duration_ms=suspension["foregroundDurationMs"],
                suspension_duration_ms=suspension["suspensionDurationMs"], suspension_count=suspension["suspensionCount"],
                threshold_ms=suspension["thresholdMs"]))
    return output


def validate(client, app_id):
    """写入隔离 UUID 的固定数据并检查去重、Issue、FPS 和挂起率结果。"""
    required = ("apm_event_raw", "apm_crash_detail", "apm_event_hourly", "apm_crash_issue_hourly",
                "apm_event_hourly_mv", "apm_crash_issue_hourly_mv", "apm_device_suspension_daily",
                "apm_device_suspension_segment", "apm_frame_scene_summary", "apm_jank_detail",
                "apm_jank_event", "apm_jank_issue_hourly", "apm_jank_issue_hourly_mv", "apm_memory_sample")
    existing = {row["name"] for row in client.rows("SELECT name FROM system.tables WHERE database='apm' FORMAT JSONEachRow")}
    missing = sorted(set(required) - existing)
    if missing:
        raise RuntimeError("缺少 ClickHouse 表/视图：" + ", ".join(missing))
    events = json.loads(FIXTURE.read_text())["events"]
    data = build_rows(app_id, events)
    for table in ("apm_event_raw", "apm_jank_event", "apm_jank_detail",
                  "apm_frame_scene_summary", "apm_device_suspension_segment"):
        client.insert(table, data[table])
    raw = client.rows(f"SELECT count() AS physical, uniqExact(event_id) AS unique_ids FROM apm_event_raw "
                      f"WHERE app_id='{app_id}' FORMAT JSONEachRow")[0]
    raw_final = client.rows(f"SELECT count() AS n FROM apm_event_raw FINAL WHERE app_id='{app_id}' FORMAT JSONEachRow")[0]
    assert_equal(int(raw["unique_ids"]), 9, "原始表唯一事件数")
    assert_equal(int(raw_final["n"]), 9, "原始表 FINAL 行数")
    for table in ("apm_jank_event", "apm_jank_detail"):
        count = client.rows(f"SELECT count() AS n FROM {table} FINAL WHERE app_id='{app_id}' FORMAT JSONEachRow")[0]
        assert_equal(int(count["n"]), 3, table + " FINAL 行数")
    issues = client.rows(f"SELECT fingerprint, uniqCombined64Merge(event_ids) AS event_count, "
                         "quantilesTDigestMerge(0.5, 0.9, 0.99)(exact_duration_p) AS duration_quantiles "
                         f"FROM apm_jank_issue_hourly WHERE app_id='{app_id}' "
                         "GROUP BY fingerprint ORDER BY fingerprint FORMAT JSONEachRow")
    assert_equal(sorted(int(row["event_count"]) for row in issues), [1, 2], "Issue 事件分组")
    assert_equal(sum(len(row["duration_quantiles"]) for row in issues), 6, "Issue 分位数个数")
    fps = client.rows(f"SELECT algorithm_version, count() AS total_records, "
                      "avgIf(toFloat64(normalized_fps60), active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS average_fps, "
                      "quantileExactIf(0.50)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p50_fps, "
                      "quantileExactIf(0.10)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p90_fps, "
                      "quantileExactIf(0.01)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p99_fps "
                      f"FROM apm_frame_scene_summary FINAL WHERE app_id='{app_id}' "
                      "GROUP BY algorithm_version ORDER BY algorithm_version FORMAT JSONEachRow")
    assert_equal(len(fps), 2, "FPS 算法版本数")
    fps_by_algorithm = {row["algorithm_version"]: row for row in fps}
    assert_equal([fps_by_algorithm["fps-v1"][key] for key in
                  ("total_records", "average_fps", "p50_fps", "p90_fps", "p99_fps")],
                 [2, 40, 50, 30, 30], "fps-v1 统计")
    assert_equal([fps_by_algorithm["fps-v2"][key] for key in ("total_records", "average_fps")],
                 [1, 45], "fps-v2 统计")
    device_days_sql = ("SELECT algorithm_version, anonymous_device_id, toDate(event_time) AS utc_date, "
                       "app_version, channel, environment, os_version, device_model, "
                       "count() AS segment_records, sum(foreground_duration_ms) AS foreground_duration_ms, "
                       "sum(suspension_duration_ms) AS suspension_duration_ms, "
                       "if(sum(foreground_duration_ms) > 0, sum(suspension_duration_ms) / 1000.0 / "
                       "(sum(foreground_duration_ms) / 3600000.0), 0.0) AS suspension_seconds_per_hour "
                             f"FROM apm_device_suspension_segment FINAL WHERE app_id='{app_id}' "
                       "GROUP BY algorithm_version, anonymous_device_id, utc_date, app_version, channel, environment, os_version, device_model")
    suspension = client.rows("SELECT algorithm_version, sum(segment_records) AS total_records, "
                             "countIf(foreground_duration_ms > 0) AS valid_records, "
                             "avgIf(suspension_seconds_per_hour, foreground_duration_ms > 0) AS average_value "
                             f"FROM ({device_days_sql}) GROUP BY algorithm_version ORDER BY algorithm_version "
                             "SETTINGS prefer_column_name_to_alias = 1 FORMAT JSONEachRow")
    assert_equal(len(suspension), 2, "挂起算法版本数")
    for row in suspension:
        expected_count, expected_rate = (2, 3.0) if row["algorithm_version"] == "suspension-v1" else (1, 2.0)
        assert_equal([int(row["total_records"]), int(row["valid_records"])], [expected_count, 1], "挂起有效设备日")
        if abs(float(row["average_value"]) - expected_rate) >= 0.0001:
            raise AssertionError("挂起率错误：" + row["algorithm_version"])
    fps_trend = client.rows(f"SELECT toStartOfHour(event_time, 'UTC') AS bucket_start, "
                            "algorithm_version, avgIf(toFloat64(normalized_fps60), "
                            "active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS average_value, "
                            "quantileExactIf(0.50)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p50_value, "
                            "quantileExactIf(0.10)(normalized_fps60, active_duration_ms > 0 AND ui_refresh_frame_count > 0) AS p90_value "
                            f"FROM apm_frame_scene_summary FINAL WHERE app_id='{app_id}' "
                            "GROUP BY bucket_start, algorithm_version ORDER BY bucket_start, algorithm_version FORMAT JSONEachRow")
    assert_equal(len(fps_trend), 2, "FPS 趋势点数")
    assert_equal([fps_trend[0][key] for key in ("average_value", "p50_value", "p90_value")],
                 [40, 50, 30], "FPS 首个趋势点")
    assert_equal(fps_trend[1]["average_value"], 45, "FPS 第二个趋势点")
    suspension_trend = client.rows("SELECT toDateTime(utc_date, 'UTC') AS bucket_start, "
                                   "algorithm_version, sum(segment_records) AS total_records, "
                                   "avgIf(suspension_seconds_per_hour, foreground_duration_ms > 0) AS average_value "
                                   f"FROM ({device_days_sql}) GROUP BY utc_date, algorithm_version "
                                   "ORDER BY bucket_start, algorithm_version "
                                   "SETTINGS prefer_column_name_to_alias = 1 FORMAT JSONEachRow")
    assert_equal(len(suspension_trend), 2, "挂起趋势点数")
    fact_ids = sorted(row["event_id"] for row in client.rows(
        f"SELECT event_id FROM apm_jank_event FINAL WHERE app_id='{app_id}' FORMAT JSONEachRow"))
    detail_ids = sorted(row["event_id"] for row in client.rows(
        f"SELECT event_id FROM apm_jank_detail FINAL WHERE app_id='{app_id}' FORMAT JSONEachRow"))
    assert_equal(fact_ids, detail_ids, "卡顿事实和详情事件集合")
    print("ClickHouse 固定数据集验证通过，app_id=" + app_id)
    print("raw=" + compact(raw) + "，fps=" + compact(fps) + "，suspension=" + compact(suspension))


def main():
    """解析参数并在显式请求时初始化 schema，然后执行数据验证。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-id", type=uuid.UUID, default=uuid.uuid4(), help="验收应用 UUID，默认随机生成")
    parser.add_argument("--initialize-schema", action="store_true", help="先初始化 001～005 schema，仅用于空测试库")
    args = parser.parse_args()
    values = read_local_env()
    user = values.get("CLICKHOUSE_ADMIN_USER") or values.get("CLICKHOUSE_USERNAME")
    password = values.get("CLICKHOUSE_ADMIN_PASSWORD") or values.get("CLICKHOUSE_PASSWORD")
    if not user or not password:
        raise ValueError("缺少 ClickHouse 用户或密码。")
    client = ClickHouse(values.get("CLICKHOUSE_HTTP_URL") or "http://127.0.0.1:8123",
                        values.get("CLICKHOUSE_DATABASE") or "apm", user, password)
    if args.initialize_schema:
        initialize_schema(client)
    validate(client, str(args.app_id))


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, RuntimeError, ValueError, urllib.error.URLError) as error:
        sys.exit("验收失败：" + str(error))
