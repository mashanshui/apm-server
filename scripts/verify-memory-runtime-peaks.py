# -*- coding: utf-8 -*-
"""通过真实 SDK、临时 Servlet/ClickHouse 和官方 MCP 完成内存峰值联合验收。

凭据仅从标准输入接收。原 APK、内存目录和网络状态在 finally 恢复；提交证据仅保留摘要与哈希。
"""
import base64
import copy
import datetime as dt
import gzip
import hashlib
import http.client
import http.server
import io
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

# 当前仓库、相邻 Android 检出和固定测试包，不访问业务环境。
ROOT = Path(__file__).resolve().parents[1]
CLIENT = Path(os.environ.get('PERFORMANCE_PROJECT_DIR', ROOT.parents[1] / 'AndroidStudioProjects' / 'Performance'))
PACKAGE = 'com.shanshui.performance'
MEMORY = 'no_backup/performance-memory-reporter'
BUILD = ROOT / 'backend/build'
PRIVATE = Path(tempfile.mkdtemp(prefix='memory-joint-private-'))
CONFIG = json.load(sys.stdin)
REPORT = {'device': 'Pixel_6/API35/arm64-v8a', 'checks': {}, 'events': []}


def progress(message):
    """发布不含凭据或设备身份的阶段进度，供当前联合会话观察。"""
    with (BUILD / 'memory-peak-joint-progress.txt').open('a') as output:
        output.write(message + '\n')
    print(message, flush=True)


def command(args, *, cwd=None, env=None, data=None, check=True):
    """执行参数数组，不把凭据插入 shell 或错误输出。"""
    # 构建和 adb 输出仅保存到私有目录，失败时只报告命令类别。
    result = subprocess.run(args, cwd=cwd, env=env, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    if check and result.returncode:
        (PRIVATE / 'last-command.log').write_bytes(result.stdout + result.stderr)
        raise RuntimeError(f'{args[0]} 执行失败，私有日志保留于 {PRIVATE}')
    return result


def adb(*args, **kwargs):
    """只操作本次明确选择的模拟器。"""
    return command(['adb', '-s', 'emulator-5554', *args], **kwargs)


def exists(path):
    """查询应用私有路径存在性，不把路径当作 shell 代码拼接。"""
    return adb('shell', 'run-as', PACKAGE, 'test', '-e', path, check=False).returncode == 0


def read_json(path):
    """优先读取原子替换备份，与生产恢复选择相同。"""
    # 原文件与备份都只进入当前进程内存，不写入提交证据。
    for candidate in (path + '.bak', path):
        if exists(candidate):
            try:
                return json.loads(adb('exec-out', 'run-as', PACKAGE, 'cat', candidate).stdout)
            except (ValueError, RuntimeError):
                pass
    return None


def current():
    """读取当前轮完整快照。"""
    return read_json(MEMORY + '/current.json')


def wait_for(label, predicate, seconds=12):
    """有限等待真实可观察状态，不以固定睡眠替代断言。"""
    # 每次检查最多执行短 adb/HTTP 请求。
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(.25)
    raise AssertionError('等待超时：' + label)


def canonical(value):
    """原事件哈希不受 JSON 属性顺序影响。"""
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def request(method, path, body=None, *, headers=None):
    """使用临时测试服务，状态错误由调用处断言，不输出认证头。"""
    # JSON 编码和显式认证头均只保留在内存。
    data = None if body is None else json.dumps(body).encode()
    actual_headers = {'Content-Type': 'application/json', **(headers or {})}
    try:
        response = urllib.request.urlopen(urllib.request.Request(CONFIG['origin'] + path, data=data, headers=actual_headers, method=method), timeout=15)
    except urllib.error.HTTPError as failure:
        response = failure
    return response.status, json.loads(response.read())


def clickhouse(sql):
    """只查询协调器给出的临时 ClickHouse，结果不含凭据。"""
    # 公开测试账号仍通过 Authorization 传递，不进入命令行。
    authentication = base64.b64encode((CONFIG['clickhouseUser'] + ':' + CONFIG['clickhousePassword']).encode()).decode()
    query = urllib.request.Request(CONFIG['clickhouseUrl'] + '/?database=apm', data=(sql + ' FORMAT JSONEachRow').encode(),
                                   headers={'Authorization': 'Basic ' + authentication}, method='POST')
    with urllib.request.urlopen(query, timeout=15) as response:
        return [json.loads(line) for line in response.read().decode().splitlines() if line]


def stored(event):
    """按授权应用和固定事件 ID 有界读取一轮，验证所有原元数据及上下文。"""
    # appId/eventId 均先按 UUID 校验，禁止任意 SQL 内容进入查询。
    app, event_id = str(uuid.UUID(CONFIG['appId'])), str(uuid.UUID(event['eventId']))
    rows = clickhouse(f"SELECT *, toUnixTimestamp64Milli(event_time) AS ended FROM apm_memory_peak_summary FINAL WHERE app_id='{app}' AND event_id='{event_id}' LIMIT 2")
    if not rows:
        return None
    assert len(rows) == 1
    row = rows[0]
    for wire, column in [('sessionId', 'session_id'), ('processId', 'process_id'), ('appVersion', 'app_version'), ('buildId', 'build_id'), ('packageName', 'package_name')]:
        assert row[column] == event[wire], wire
    assert int(row['version_code']) == event['versionCode']
    assert int(row['ended']) == event['occurredAt']
    assert int(row['collection_started_at']) == event['memoryPeakSummary']['collectionStartedAt']
    for metric, column in [('pss', 'pss'), ('vss', 'vss'), ('javaHeap', 'java_heap')]:
        peak = event['memoryPeakSummary'].get(metric)
        bytes_column = column + ('_used_bytes' if column == 'java_heap' else '_bytes')
        if peak is None:
            assert row[bytes_column] is None
        else:
            assert int(row[bytes_column]) == peak['bytes']
            assert int(row[column + '_occurred_at']) == peak['occurredAt']
            assert bool(row[column + '_foreground']) == peak['foreground']
            assert row[column + '_scene'] == peak.get('scene')
    return row


class Proxy(http.server.BaseHTTPRequestHandler):
    """只在回环入口注入可重复故障，所有真正写入仍由真实后端完成。"""
    # 宿主按阶段切换的故障模式。
    mode = 'normal'
    # 原始请求只驻留当前私有会话，不进入提交证据。
    entries = []
    # HTTP 工作线程与宿主观察线程共享记录的互斥锁。
    lock = threading.Lock()

    def log_message(self, *_):
        """禁用默认请求日志，避免输出认证和原始设备数据。"""

    def do_POST(self):
        """记录原事件摘要并按模式转发、503 或丢弃真实写入后的响应。"""
        # 只读取本次 SDK 批次，原始字节不写入公开报告。
        raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        # 解码后的信封用于逐事件身份及载荷哈希断言。
        payload = json.loads(gzip.decompress(raw) if self.headers.get('Content-Encoding') == 'gzip' else raw)
        # 在返回响应前记录模式和原事件，避免事后模式变化混淆证据。
        entry = {'at': int(time.time() * 1000), 'events': payload['events'], 'mode': type(self).mode}
        if len(payload['events']) == 1:
            entry['attemptsBeforeResponse'] = (read_json(MEMORY + '/events/' + payload['events'][0]['eventId'] + '.json') or {}).get('attempts', 0)
        with type(self).lock:
            type(self).entries.append(entry)
        progress('代理收到内存批次 mode=' + entry['mode'])
        if entry['mode'] == 'disconnect':
            self.connection.close()
            return
        if entry['mode'] == '503':
            self.send_response(503)
            self.send_header('Retry-After', '2')
            self.send_header('Connection', 'close')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        # 转发请求头只选择当前必需字段，不记录 Key。
        destination = urllib.parse.urlparse(CONFIG['origin'])
        # 保持原认证、Schema 和压缩方式。
        headers = {name: self.headers[name] for name in ['X-App-Key', 'X-Schema-Version', 'Content-Type', 'Content-Encoding'] if self.headers.get(name)}
        # 唯一上游为父测试传入的隔离后端。
        connection = http.client.HTTPConnection(destination.hostname, destination.port, timeout=15)
        try:
            connection.request('POST', self.path, body=raw, headers=headers)
            # 真实入库响应，只有显式丢包阶段阻止它到达 SDK。
            response = connection.getresponse()
            # 原响应体保留用于 accepted/duplicate 断言。
            body = response.read()
            entry['status'] = response.status
            entry['response'] = json.loads(body)
            progress('代理转发完成 status=' + str(response.status))
            if entry['mode'] == 'lose-once':
                # 已真实 accepted 后阻断响应及随后的自动连接重试，给宿主验证保留状态。
                type(self).mode = 'disconnect'
                entry['lost'] = True
                self.connection.close()
                return
            self.send_response(response.status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Connection', 'close')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception as failure:
            entry['errorType'] = type(failure).__name__
            progress('代理转发异常 type=' + type(failure).__name__)
            raise
        finally:
            connection.close()


def entries_for(event_id):
    """复制已观察请求，不把列表中的原始载荷写入公开报告。"""
    with Proxy.lock:
        return [entry for entry in Proxy.entries if any(event['eventId'] == event_id for event in entry['events'])]


def stop():
    """停止真实应用进程，不清除应用数据。"""
    adb('shell', 'am', 'force-stop', PACKAGE)


def launch():
    """启动清单中的真实主 Activity。"""
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')


def snapshot_tar():
    """备份只属于内存 Reporter 的目录，原目录另以重命名保留。"""
    return adb('exec-out', 'run-as', PACKAGE, 'tar', '-C', 'no_backup', '-cf', '-', 'performance-memory-reporter').stdout


def tar_digest(raw):
    """对文件内容比较，忽略 tar 容器顺序和目录时间。"""
    with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        return {member.name: hashlib.sha256(archive.extractfile(member).read()).hexdigest() for member in archive if member.isfile()}


def build(version, code, port, instrument=False):
    """同一源代码分别构建 A/B，凭据通过 Gradle 环境属性注入。"""
    # 属性名称可含点，使用环境字典而非 shell export 或命令行秘密。
    environment = os.environ.copy()
    for key, value in {'appKey': CONFIG['appKey'], 'baseUrl': f'http://127.0.0.1:{port}', 'memoryJointTest': 'true', 'versionName': version, 'versionCode': str(code)}.items():
        environment['ORG_GRADLE_PROJECT_performance.' + key] = value
    # A 包额外构建准备测试，B 包只负责覆盖升级。
    tasks = [':app:assembleDebug'] + ([':app:assembleDebugAndroidTest'] if instrument else [])
    command(['bash', 'gradlew', '-Pperformance.testBuildType=debug', *tasks], cwd=CLIENT, env=environment)
    # 在下一次构建覆盖输出目录前冻结当前 APK。
    output = PRIVATE / (version + '.apk')
    shutil.copyfile(CLIENT / 'app/build/outputs/apk/debug/app-debug.apk', output)
    # 构建插件导出的元数据，与 APK 和原事件共同对齐。
    metadata = json.loads((CLIENT / 'app/build/outputs/build-info/debug/build-info.json').read_text())
    assert metadata['outputs'][0]['versionName'] == version
    assert 'PERFORMANCE_MEMORY_JOINT_TEST = true' in (CLIENT / 'app/build/generated/source/buildConfig/debug/com/shanshui/performance/BuildConfig.java').read_text()
    REPORT.setdefault('builds', {})[version] = {'versionCode': code, 'buildId': metadata['buildId'], 'sha256': hashlib.sha256(output.read_bytes()).hexdigest()}
    if instrument:
        shutil.copyfile(CLIENT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', PRIVATE / 'joint-test.apk')
    return output


def install(path):
    """使用 Android CLI 覆盖安装，保留全部应用数据。"""
    command(['android', 'install', '--device', 'emulator-5554', '--apks', str(path), '--install-options=-r'])


def iso(milliseconds):
    """毫秒时间转换为接口需要的 UTC ISO 字符串。"""
    return dt.datetime.fromtimestamp(milliseconds / 1000, dt.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')


def stats(values):
    """按共同契约独立计算算术平均与线性插值，不能从趋势重算概览。"""
    # 升序峰值序列，合法零值不删除。
    ordered = sorted(values)
    if not ordered:
        return {'runCount': 0, **{key: None for key in ['averageBytes', 'p50Bytes', 'p90Bytes', 'p95Bytes', 'p99Bytes']}}
    # 每轮只贡献一个值，不按设备或运行时长加权。
    result = {'runCount': len(ordered), 'averageBytes': sum(ordered) / len(ordered)}
    for percentile in [50, 90, 95, 99]:
        # 共同线性插值公式及其左侧下标。
        index = (len(ordered) - 1) * percentile / 100
        lower = math.floor(index)
        result[f'p{percentile}Bytes'] = ordered[lower] + (ordered[math.ceil(index)] - ordered[lower]) * (index - lower)
    return result


def assert_stats(actual, expected):
    """逐字段核对数量、null 与数值，容忍浮点舍入而不把缺失改成零。"""
    assert actual['status'] == ('ok' if expected['runCount'] else 'no_data')
    for key, value in expected.items():
        if value is None:
            assert actual[key] is None, key
        else:
            assert math.isclose(actual[key], value, rel_tol=1e-12, abs_tol=1e-4), key


def verify_queries(events):
    """真实 ClickHouse/HTTP/Agent/MCP 对齐原设备事件的统计与独立上下文。"""
    # 时间只按已持久化的运行截止；查询范围不超过当前所需窗口。
    params = {'from': iso(min(e['occurredAt'] for e in events) - 60_000), 'to': iso(int(time.time() * 1000) + 60_000), 'appVersion': 'memory-joint-A'}
    filters = [{}, {'foreground': True}, {'foreground': False}]
    scenes = sorted({p['scene'] for e in events for p in e['memoryPeakSummary'].values() if isinstance(p, dict) and p.get('scene')})
    filters += [{'scene': scene, 'foreground': True} for scene in scenes]
    authorization = {'Authorization': 'Bearer ' + CONFIG['token']}
    for selected_filter in filters:
        encoded = urllib.parse.urlencode({**params, **{k: str(v).lower() if isinstance(v, bool) else v for k, v in selected_filter.items()}})
        status, summary = request('GET', '/api/agent/v1/memory-metrics/summary?' + encoded, headers=authorization)
        assert status == 200 and summary['dataSource'] == 'clickhouse'
        assert 'sampleCount' not in json.dumps(summary)
        for metric, wire in [('pss', 'pss'), ('vss', 'vss'), ('java_heap', 'javaHeap')]:
            # 每个指标匹配它自己的峰值上下文，不能借用另一项。
            selected = [e for e in events if e['memoryPeakSummary'].get(wire) and all(e['memoryPeakSummary'][wire].get(k) == v for k, v in selected_filter.items())]
            assert_stats(summary[wire], stats([e['memoryPeakSummary'][wire]['bytes'] for e in selected]))
            status, trend = request('GET', '/api/agent/v1/memory-metrics/trend?' + encoded + '&metric=' + metric + '&interval=hour', headers=authorization)
            assert status == 200
            for point in trend['points']:
                start = int(dt.datetime.fromisoformat(point['bucketStart'].replace('Z', '+00:00')).timestamp() * 1000)
                values = [e['memoryPeakSummary'][wire]['bytes'] for e in selected if e['occurredAt'] // 3_600_000 * 3_600_000 == start]
                assert_stats(point, stats(values))
    # 官方 MCP 客户端再与真实 Agent HTTP 比较全部指标和同一筛选。
    config = {**{key: CONFIG[key] for key in ['origin', 'token', 'appId']}, **params,
              'filters': [{'appVersion': 'memory-joint-A', **f} for f in filters], 'metrics': ['pss', 'vss', 'java_heap']}
    command(['node', 'scripts/verify-memory-runtime-peaks.mjs'], cwd=ROOT / 'mcp-server', data=json.dumps(config).encode())
    REPORT['checks']['J5_device_http_mcp'] = True
    # 仅保存合成负载的聚合统计，页面据此核对 MiB；不保存原设备身份或完整事件。
    REPORT['deviceSummary'] = {wire: stats([e['memoryPeakSummary'][wire]['bytes'] for e in events if e['memoryPeakSummary'].get(wire)])
                               for wire in ['pss', 'vss', 'javaHeap']}
    return params


def storage_mode(mode):
    """只控制本测试 Java Bean 的数据库名，确认后才发请求。"""
    temporary = BUILD / 'memory-peak-joint-control.new'
    temporary.write_text(json.dumps({'mode': mode}))
    temporary.replace(BUILD / 'memory-peak-joint-control.json')
    acknowledgment = BUILD / 'memory-peak-joint-control.ack'
    wait_for('存储故障模式确认', lambda: acknowledgment.exists() and acknowledgment.read_text() == mode)


def verify_contracts():
    """同一真实 HTTP/持久库复验历史窗口、非法输入与可恢复存储故障。"""
    fixture = json.loads((ROOT / 'docs/fixtures/memory-runtime-peaks.json').read_text())
    def event_at(ended):
        """平移共同固定样例，保留完整且有序的独立峰值。"""
        event = copy.deepcopy(fixture)
        event.update(eventId=str(uuid.uuid4()), occurredAt=ended, appVersion='joint-contract')
        event['memoryPeakSummary'].update(collectionStartedAt=ended - 10_000, lastSampledAt=ended)
        for name in ['pss', 'vss', 'javaHeap']:
            event['memoryPeakSummary'][name]['occurredAt'] = ended - 1_000
        return event

    def ingest(event, key=None):
        """上传一条合成边界样例，认证信息不写入证据。"""
        return request('POST', '/ingest/v1/batches', {'requestId': str(uuid.uuid4()), 'events': [event]}, headers={'X-App-Key': key or CONFIG['appKey'], 'X-Schema-Version': '2'})

    now = int(time.time() * 1000)
    old = event_at(now - 8 * 86_400_000)
    assert ingest(old)[1]['accepted'] == 1
    assert stored(old)
    for ended, code in [(now - 90 * 86_400_000, 'EVENT_TIME_TOO_OLD'), (now + 3_600_000, 'EVENT_TIME_IN_FUTURE')]:
        status, result = ingest(event_at(ended))
        assert status == 200 and result['rejected'] == 1 and code in json.dumps(result)
    partial = event_at(now)
    partial['memoryPeakSummary']['vss'] = None
    partial['memoryPeakSummary']['javaHeap'] = None
    partial['memoryPeakSummary']['pss']['bytes'] = 0
    assert ingest(partial)[1]['accepted'] == 1
    invalid = event_at(now)
    invalid['processId'] = 'invalid-process'
    status, result = ingest(invalid)
    assert status == 400 and result['code'] == 'INVALID_BATCH' and 'processId' in result['message']
    invalid = event_at(now)
    invalid['packageName'] = 'com.invalid.package'
    status, result = ingest(invalid)
    assert status == 403 and result['code'] == 'PACKAGE_NAME_MISMATCH'
    status, result = ingest(event_at(now), 'invalid-joint-key')
    assert status == 401 and result['code'] == 'INVALID_APP_KEY'
    legacy = event_at(now)
    legacy['eventType'] = 'memory_sample'
    legacy.pop('memoryPeakSummary')
    legacy['memorySample'] = {'pssBytes': 10, 'processName': PACKAGE, 'foreground': True}
    status, result = ingest(legacy)
    assert status == 400 and result['code'] == 'INVALID_BATCH'
    other = event_at(now - 8 * 86_400_000)
    other['eventType'] = 'app_start'
    other.pop('memoryPeakSummary')
    status, result = ingest(other)
    assert status == 200 and 'EVENT_TIME_TOO_OLD' in json.dumps(result)
    unavailable = event_at(now)
    try:
        storage_mode('fail')
        status, result = ingest(unavailable)
        assert status == 503 and result['retryable'] is True
        status, result = request('GET', '/api/agent/v1/memory-metrics/summary', headers={'Authorization': 'Bearer ' + CONFIG['token']})
        assert status == 503 and result['code'] == 'EVENT_STORE_UNAVAILABLE'
    finally:
        storage_mode('normal')
    assert ingest(unavailable)[1]['accepted'] == 1
    assert ingest(unavailable)[1]['duplicate'] == 1
    assert stored(unavailable)
    REPORT['checks']['J6_http_contract_and_storage'] = True


def fault(stage, old):
    """在真实进程的生产存储边界 SIGKILL，检查唯一副本及封存状态。"""
    marker = 'startup boundary reached stage=' + stage
    before = adb('logcat', '-d', '-s', 'MemoryJointFault:I').stdout.count(marker.encode())
    adb('exec-in', 'run-as', PACKAGE, 'tee', 'no_backup/memory-joint-startup-fault', data=stage.encode())
    launch()
    wait_for('命中启动中断点 ' + stage, lambda: adb('logcat', '-d', '-s', 'MemoryJointFault:I').stdout.count(marker.encode()) > before)
    stop()
    assert canonical(current()) == canonical(old)
    record = read_json(MEMORY + '/events/' + old['eventId'] + '.json')
    assert (record is None) if stage == 'write' else (record is not None and canonical(record['event']) == canonical(old))


def run():
    """备份、采集、重启、失败重试、覆盖升级、查询与恢复形成一个有界会话。"""
    # 原目录通过重命名保持完整，名称仅用于当前设备会话。
    backup = 'no_backup/performance-memory-reporter.joint-backup-' + uuid.uuid4().hex
    # 测试目录移到此处后再删除，不能删除恢复后的原目录。
    results = 'no_backup/performance-memory-reporter.joint-results-' + uuid.uuid4().hex
    # 记录原目录存在性、已移动状态、内容哈希及反向端口创建状态。
    had_memory, moved, original_digest, reversed_port = exists(MEMORY), False, None, False
    for name in ['memory-peak-joint.done', 'memory-peak-joint.json', 'memory-peak-joint-report.json', 'memory-peak-joint-progress.txt']:
        (BUILD / name).unlink(missing_ok=True)
    # 端口由系统分配，入口限定回环。
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Proxy)
    # 此端口通过 adb reverse 提供给唯一测试模拟器。
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    stop()
    # 原包和测试包只进入私有临时目录，恢复成功才清理。
    originals = {}
    for package in [PACKAGE, PACKAGE + '.test']:
        paths = adb('shell', 'pm', 'path', package, check=False).stdout.decode().strip().splitlines()
        if paths:
            output = PRIVATE / (package + '-original.apk')
            adb('pull', paths[0].removeprefix('package:'), str(output))
            originals[package] = output
    assert PACKAGE in originals
    wifi = adb('shell', 'settings', 'get', 'global', 'wifi_on').stdout.strip() == b'1'
    data = adb('shell', 'settings', 'get', 'global', 'mobile_data').stdout.strip() == b'1'
    try:
        if had_memory:
            original = snapshot_tar()
            (PRIVATE / 'original-memory.tar').write_bytes(original)
            original_digest = tar_digest(original)
            adb('shell', 'run-as', PACKAGE, 'mv', MEMORY, backup)
            moved = True
        adb('reverse', 'tcp:' + str(port), 'tcp:' + str(port))
        reversed_port = True
        progress('正在构建隔离联调 A/B 测试包')
        first_apk = build('memory-joint-A', 1, port, True)
        second_apk = build('memory-joint-B', 2, port)
        install(first_apk)
        install(PRIVATE / 'joint-test.apk')
        test = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PACKAGE + '.MemoryJointPreparationTest', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner').stdout.decode()
        (PRIVATE / 'device-preparation.log').write_text(test)
        assert 'OK (1 test)' in test, '设备首轮测试未通过'
        assert 'AssumptionViolatedException' not in test
        stop()
        first = current()
        assert first and first['appVersion'] == 'memory-joint-A'
        assert not entries_for(first['eventId']) and stored(first) is None
        REPORT['checks']['J1_current_private'] = True
        progress('J1 首轮、内存负载、Activity 与网络恢复通过')

        Proxy.mode = '503'
        launch()
        wait_for('旧轮503', lambda: entries_for(first['eventId']))
        record_path = MEMORY + '/events/' + first['eventId'] + '.json'
        wait_for('503重试状态落盘', lambda: (read_json(record_path) or {}).get('attempts', 0) >= 1)
        assert canonical(read_json(record_path)['event']) == canonical(first)
        second = wait_for('新轮current', lambda: current() and current()['eventId'] != first['eventId'] and current())
        assert second['processId'] != first['processId']
        Proxy.mode = 'lose-once'
        progress('J3 已观察503与原任务保留，等待真实写入后的响应丢失')
        loss = wait_for('服务端写入后响应丢失', lambda: next((e for e in entries_for(first['eventId']) if e.get('lost')), None), 180)
        wait_for('响应丢失后仍保留', lambda: (read_json(record_path) or {}).get('attempts', 0) > loss['attemptsBeforeResponse'])
        assert stored(first)
        assert canonical(read_json(record_path)['event']) == canonical(first)
        Proxy.mode = 'normal'
        progress('J3 已观察服务端写入及客户端保留，等待原ID duplicate确认')
        # 等待实际保存的退避截止，再给一次上传周期余量；不修改 SDK 的退避常量。
        retry_wait = min(600, max(0, (read_json(record_path)['nextAttemptAtMillis'] - time.time() * 1000) / 1000) + 65)
        wait_for('合法duplicate确认', lambda: any(e.get('response', {}).get('duplicate', 0) >= 1 and not e.get('lost') for e in entries_for(first['eventId'])), retry_wait)
        wait_for('确认后删除', lambda: not any(exists(record_path + suffix) for suffix in ['', '.bak', '.new']))
        assert all(canonical(e) == canonical(first) for entry in entries_for(first['eventId']) for e in entry['events'] if e['eventId'] == first['eventId'])
        assert not entries_for(second['eventId'])
        REPORT['checks']['J3_503_lost_response_duplicate'] = True
        progress('J3 503、连接丢失、原载荷重试及duplicate确认通过')

        stop()
        second = current()
        fault('write', second)
        fault('remove', second)
        adb('shell', 'run-as', PACKAGE, 'rm', 'no_backup/memory-joint-startup-fault')
        assert not entries_for(second['eventId'])
        REPORT['checks']['J2_startup_fault_boundaries'] = True
        install(second_apk)
        launch()
        wait_for('升级补报原A版本', lambda: stored(second), 20)
        wait_for('升级后确认清理', lambda: not exists(MEMORY + '/events/' + second['eventId'] + '.json'))
        third = wait_for('B当前轮', lambda: current() and current()['appVersion'] == 'memory-joint-B' and current())
        assert third['eventId'] not in [first['eventId'], second['eventId']]
        assert not entries_for(third['eventId']) and stored(third) is None
        REPORT['checks']['J4_upgrade_original_metadata'] = True
        progress('J2/J4 启动中断、同ID恢复、升级仍归属原版本通过')

        params = verify_queries([first, second])
        verify_contracts()
        # 固定集另经官方 MCP 查询，时间窗与设备事件分开，保留独立上下文断言。
        command(['node', 'scripts/verify-memory-runtime-peaks.mjs'], cwd=ROOT / 'mcp-server', data=json.dumps({k: CONFIG[k] for k in ['origin', 'token', 'appId', 'from', 'to']}).encode())
        for alias, event in [('A1', first), ('A2', second)]:
            REPORT['events'].append({'alias': alias, 'eventIdSha256': hashlib.sha256(event['eventId'].encode()).hexdigest(),
                                     'processIdSha256': hashlib.sha256(event['processId'].encode()).hexdigest(),
                                     'payloadSha256': canonical(event), 'appVersion': event['appVersion'], 'buildId': event['buildId'],
                                     'requests': len(entries_for(event['eventId']))})
        REPORT['checks']['J7_client_frequency_evidence'] = True
        REPORT['query'] = params
        # 页面协调文件不含上报Key、Token或设备身份，仍只保留在忽略目录。
        (BUILD / 'memory-peak-joint.json').write_text(json.dumps({'origin': CONFIG['origin'], 'appId': CONFIG['appId'], 'query': params}))
        (BUILD / 'memory-peak-joint-report.json').write_text(json.dumps(REPORT, ensure_ascii=False, indent=2))
        progress('HTTP/ClickHouse/Agent/MCP 通过，等待真实网页核对')
        wait_for('网页核对完成', lambda: (BUILD / 'memory-peak-joint.done').exists(), 600)
        assert not entries_for(third['eventId']) and stored(third) is None
    except BaseException:
        # 仅在私有临时目录留原始故障状态，不进入提交证据或控制台。
        try:
            failure_state = {'requests': Proxy.entries, 'current': current()}
            if 'first' in locals():
                failure_state['queued'] = read_json(MEMORY + '/events/' + first['eventId'] + '.json')
            (PRIVATE / 'failure-state.json').write_text(json.dumps(failure_state))
        except Exception:
            pass
        raise
    finally:
        stop()
        # 故障探针只删除本次固定标记，不清空应用其他目录。
        if exists('no_backup/memory-joint-startup-fault'):
            adb('shell', 'run-as', PACKAGE, 'rm', 'no_backup/memory-joint-startup-fault')
        if exists(MEMORY):
            adb('shell', 'run-as', PACKAGE, 'mv', MEMORY, results)
        if moved:
            adb('shell', 'run-as', PACKAGE, 'mv', backup, MEMORY)
            assert tar_digest(snapshot_tar()) == original_digest
        for package in [PACKAGE, PACKAGE + '.test']:
            if package in originals:
                adb('install', '-r', '-d', '-t', str(originals[package]))
        adb('shell', 'svc', 'wifi', 'enable' if wifi else 'disable')
        adb('shell', 'svc', 'data', 'enable' if data else 'disable')
        if reversed_port:
            adb('reverse', '--remove', 'tcp:' + str(port))
        if exists(results):
            adb('shell', 'run-as', PACKAGE, 'rm', '-r', results)
        server.shutdown()
        REPORT['restoredOriginalMemoryAndApks'] = True
        (BUILD / 'memory-peak-joint-report.json').write_text(json.dumps(REPORT, ensure_ascii=False, indent=2))
    progress('联合设备与持久化检查完成，原目录/APK/网络开关已恢复')
    shutil.rmtree(PRIVATE)


def terminate(*_):
    """父进程结束时仍执行应用恢复，不将测试包留作正常版本。"""
    raise SystemExit('联合测试被中断，执行恢复')


signal.signal(signal.SIGTERM, terminate)
run()
