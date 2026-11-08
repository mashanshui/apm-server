"""宿主自报语义、当前引用核对、报告授权与脱敏；不证明模型质量。"""
import hashlib
import json
import pytest
from apm_agent_worker.analysis import validated
from apm_agent_worker.platform import WorkerError

# 合成标识，不能关联真实应用或设备。
RUN = '22222222-2222-4222-8222-222222222222'
EVIDENCE = {
    'evidenceId': '11111111-1111-4111-8111-111111111111', 'schemaVersion': 3,
    'event': {'eventId': 'event', 'appId': 'app', 'crashKind': 'jvm', 'fatal': True, 'groupingKind': 'temporary'},
    'mapping': {'status': 'pending', 'reason': 'awaiting_processing', 'symbolId': None, 'revision': None, 'sha256': None, 'processedAt': None},
    'fragments': [{'id': 'crash-stack', 'kind': 'crash-stack', 'format': 'raw_chain', 'content': {
        'kind': 'jvm', 'fatal': True, 'throwableChain': [{'type': 'java.lang.IllegalStateException', 'message': 'test',
            'frames': [{'className': 'Example', 'methodName': 'crash', 'fileName': 'Example.kt', 'lineNumber': 2, 'applicationFrame': True}]}]}}],
}



def model_result():
    """宿主提供片段；来源、当前检查和摘要由工具填充。"""
    return {'schemaVersion': 4, 'evidenceId': EVIDENCE['evidenceId'], 'runId': RUN,
            'conclusion': 'ROOT_CAUSE_CANDIDATE', 'summary': '当前代码候选',
            'candidates': [{'title': '显式抛出', 'reason': '触发条件未知', 'evidenceRefs': ['crash-stack']}],
            'sourceRefs': [{'path': 'Example.kt', 'startLine': 2, 'endLine': 2, 'snippet': 'throw IllegalStateException()'}],
            'unknowns': [], 'risks': [], 'fixSuggestions': [], 'validationSuggestions': []}


@pytest.mark.parametrize('current,expected', [('fun current() = 7', 'CURRENT_MATCH'), ('fun current() = 8', 'CURRENT_DIFFERENT')])
def test_android_prefixed_reference_uses_current_report_and_preserves_workspace(tmp_path, current, expected):
    """报告以平台根相对 Android 路径核对当前引用，保持文件与索引原样。"""
    from test_source import workspace
    root = workspace(tmp_path)  # 实际 Git 根；假证据不访问平台。
    source = root / 'android/performance-metrics/src/main/Current.kt'  # 当前未提交源码。
    source.parent.mkdir(parents=True)
    source.write_text(current + '\n')
    index = (root / '.git/index').read_bytes()  # 回传核对不得修改索引。
    result = model_result()  # 现有版本 4 的合成报告。
    result['sourceRefs'] = [{'path': 'android/performance-metrics/src/main/Current.kt', 'startLine': 1, 'endLine': 1, 'snippet': 'fun current() = 7'}]
    checked = validated(json.dumps(result), EVIDENCE, root, RUN)  # 只核对当前片段。
    assert checked['schemaVersion'] == 4
    assert checked['sourceRefs'][0]['path'] == result['sourceRefs'][0]['path']
    assert checked['sourceRefs'][0]['currentCheck'] == expected
    assert source.read_text() == current + '\n'
    assert (root / '.git/index').read_bytes() == index


@pytest.mark.parametrize('format', ['raw_chain', 'retrace_text'])
def test_only_current_single_stack_evidence_is_supported(tmp_path, format):
    """原始与固定还原材料均支持，同一任务只有 crash-stack 一个片段。"""
    evidence = json.loads(json.dumps(EVIDENCE))  # 各格式独立输入，不能改写其他测试的冻结材料。
    if format == 'retrace_text':
        evidence['event']['groupingKind'] = 'resolved'
        evidence['mapping'] = {'status': 'symbolicated', 'symbolId': 'original-symbol', 'revision': 1, 'sha256': 'a' * 64, 'processedAt': '2026-11-05T00:00:00Z'}
        evidence['fragments'][0].update(format='retrace_text', content='java.lang.Exception\n\tat Example.a(Source:1)\n<OR> at Other.b(Source:2)')
    assert validated(json.dumps(model_result()), evidence, tmp_path, RUN)['schemaVersion'] == 4


@pytest.mark.parametrize('failure', ['version', 'old-fragment', 'two-stacks', 'wrong-format', 'old-state'])
def test_rejects_old_or_inconsistent_evidence_without_conversion(tmp_path, failure):
    """未知版本、旧片段及状态/格式不一致明确失败，不拼接或转换材料。"""
    evidence = json.loads(json.dumps(EVIDENCE))  # 每次只改一项当前契约。
    if failure == 'version': evidence['schemaVersion'] = 2
    elif failure == 'old-fragment': evidence['fragments'][0]['id'] = 'raw-crash'
    elif failure == 'two-stacks': evidence['fragments'].append(dict(evidence['fragments'][0]))
    elif failure == 'wrong-format': evidence['fragments'][0]['format'] = 'retrace_text'
    else: evidence['mapping']['status'] = 'raw_only'
    with pytest.raises(WorkerError, match='EVIDENCE_FORMAT_UNSUPPORTED'):
        validated(json.dumps(model_result()), evidence, tmp_path, RUN)


@pytest.mark.parametrize('content,check', [('throw IllegalStateException()', 'CURRENT_MATCH'), ('fixed()', 'CURRENT_DIFFERENT'), (None, 'UNAVAILABLE')])
def test_current_match_is_distinct_from_host_read_proof(tmp_path, content, check):
    """当前匹配不证明历史读取，已修复或删除位置仍允许保留宿主原引用。"""
    if content is not None:
        (tmp_path / 'Example.kt').write_text('fun crash() {\n' + content + '\n}\n')
    output = validated(json.dumps(model_result()), EVIDENCE, tmp_path, RUN)
    ref = output['sourceRefs'][0]  # 每次引用都有独立来源与提交时检查结果。
    assert ref['metadataSource'] == 'HOST_REPORTED'
    assert ref['currentCheck'] == check
    assert ref['snippetSha256'] == hashlib.sha256(ref['snippet'].encode()).hexdigest()
    assert 'workspaceUnchanged' not in output['verification']
    assert output['repair']['metadataSource'] == 'HOST_REPORTED'


@pytest.mark.parametrize('failure', ['outside', 'secret', 'line-count', 'range', 'evidence', 'run', 'fragment', 'extra', 'legacy', 'fake-check'])
def test_invalid_reference_and_protocol_rejected(tmp_path, failure):
    """结构、任务、Run、范围、秘密路径和伪造检查结果均拒绝。"""
    result = model_result()  # 每个失败独立更改一个输入。
    if failure == 'outside': result['sourceRefs'][0]['path'] = '../outside'
    elif failure == 'secret': result['sourceRefs'][0]['path'] = 'config.local.json'
    elif failure == 'line-count': result['sourceRefs'][0]['endLine'] = 3
    elif failure == 'range': result['sourceRefs'][0]['endLine'] = 202
    elif failure == 'evidence': result['evidenceId'] = RUN
    elif failure == 'run': result['runId'] = EVIDENCE['evidenceId']
    elif failure == 'fragment': result['candidates'][0]['evidenceRefs'] = ['other']
    elif failure == 'extra': result['isFixed'] = True
    elif failure == 'legacy': result['schemaVersion'] = 3
    else: result['sourceRefs'][0]['currentCheck'] = 'CURRENT_MATCH'
    with pytest.raises(WorkerError):
        validated(json.dumps(result), EVIDENCE, tmp_path, RUN)


def test_readonly_report_cannot_claim_edits(tmp_path):
    """工具拒绝只读任务提交修改事实，不能宣称能控制宿主其他工具。"""
    result = model_result()
    result['repair'] = {'status': 'APPLIED', 'files': [{'path': 'Example.kt', 'summary': '修复'}], 'reason': ''}
    with pytest.raises(WorkerError, match='REPAIR_NOT_AUTHORIZED'):
        validated(json.dumps(result), EVIDENCE, tmp_path, RUN)
    assert validated(json.dumps(result), EVIDENCE, tmp_path, RUN, True)['repair']['status'] == 'APPLIED'


@pytest.mark.parametrize('status,commands', [('PASSED', []), ('PASSED', [{'command': 'test', 'exitCode': 1, 'summary': ''}]), ('FAILED', [{'command': 'test', 'exitCode': 0, 'summary': ''}]), ('NOT_RUN', [{'command': 'test', 'exitCode': 0, 'summary': ''}])])
def test_invalid_verification_facts_rejected(tmp_path, status, commands):
    """报告格式不能用零失败命令或空测试伪造通过。"""
    result = model_result()
    result['verification'] = {'status': status, 'commands': commands, 'reason': 'test'}
    with pytest.raises(WorkerError, match='FORMAT_INVALID'):
        validated(json.dumps(result), EVIDENCE, tmp_path, RUN)


def test_host_test_and_repair_text_redaction(tmp_path):
    """只报告已执行命令；路径与明确凭据脱敏，测试仍为宿主自报。"""
    result = model_result()
    result['verification'] = {'status': 'PASSED', 'commands': [{'command': 'python /home/user/project/test.py', 'exitCode': 0, 'summary': 'apm_aw_synthetic_secret'}], 'reason': ''}
    result['sourceRefs'][0]['snippet'] = 'val token = "apm_aw_synthetic_secret"'
    output = validated(json.dumps(result), EVIDENCE, tmp_path, RUN)
    assert 'apm_aw_synthetic_secret' not in json.dumps(output)
    assert '/home/user' not in json.dumps(output)
    assert output['verification']['metadataSource'] == 'HOST_REPORTED'


def test_insufficient_evidence_and_strict_version(tmp_path):
    """不足为正常结论，版本必须是真整数 4。"""
    result = model_result()
    result.update(conclusion='INSUFFICIENT_EVIDENCE', candidates=[], sourceRefs=[], unknowns=['输入未知'])
    assert validated(json.dumps(result), EVIDENCE, tmp_path, RUN)['conclusion'] == 'INSUFFICIENT_EVIDENCE'
    for version in (True, 4.0, '4'):
        result['schemaVersion'] = version
        with pytest.raises(WorkerError, match='FORMAT_INVALID'):
            validated(json.dumps(result), EVIDENCE, tmp_path, RUN)
