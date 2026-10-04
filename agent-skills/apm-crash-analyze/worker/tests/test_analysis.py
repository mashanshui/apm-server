"""宿主自报语义、当前引用核对、报告授权与脱敏；不证明模型质量。"""
import hashlib
import json
import pytest
from apm_agent_worker.analysis import validated
from apm_agent_worker.platform import WorkerError

# 合成标识，不能关联真实应用或设备。
RUN = '22222222-2222-4222-8222-222222222222'
EVIDENCE = {'evidenceId': '11111111-1111-4111-8111-111111111111', 'schemaVersion': 2, 'fragments': [{'id': 'raw-crash'}]}


def model_result():
    """宿主提供片段；来源、当前检查和摘要由工具填充。"""
    return {'schemaVersion': 4, 'evidenceId': EVIDENCE['evidenceId'], 'runId': RUN,
            'conclusion': 'ROOT_CAUSE_CANDIDATE', 'summary': '当前代码候选',
            'candidates': [{'title': '显式抛出', 'reason': '触发条件未知', 'evidenceRefs': ['raw-crash']}],
            'sourceRefs': [{'path': 'Example.kt', 'startLine': 2, 'endLine': 2, 'snippet': 'throw IllegalStateException()'}],
            'unknowns': [], 'risks': [], 'fixSuggestions': [], 'validationSuggestions': []}


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
