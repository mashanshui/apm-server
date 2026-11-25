package com.shanshui.performance.anr

import com.google.gson.GsonBuilder
import com.shanshui.performance.network.TransportSession
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

/** 公共批次 API 使用的单个有界事件请求。 */
internal data class AnrBatchRequest(
    /** 本次批次请求 ID。 */ val requestId: String,
    /** 待发送的已冻结事件。 */ val events: List<AnrUploadEvent>,
)

/** 服务端批次确认。 */
internal data class AnrBatchResponse(
    /** 原请求 ID。 */ val requestId: String? = null,
    /** 新接受数量。 */ val accepted: Int? = null,
    /** 拒绝数量。 */ val rejected: Int? = null,
    /** 已存在数量。 */ val duplicate: Int? = null,
    /** 服务端是否要求重试。 */ val retryable: Boolean = false,
    /** 建议重试秒数。 */ val retryAfterSeconds: Long? = null,
    /** 按事件定位的错误；缺失或空元素不能作为有效确认。 */ val errors: List<AnrBatchError?>? = null,
)

/** 服务端对单个事件的受控拒绝结果。 */
internal data class AnrBatchError(
    /** 请求中的事件下标。 */ val index: Int? = null,
    /** 被拒绝的事件 ID。 */ val eventId: String? = null,
    /** 受控错误类别。 */ val code: String? = null,
    /** 是否可重试。 */ val retryable: Boolean = false,
)

/** Retrofit 复用公共 gzip、App Key 和连接配置。 */
internal interface AnrIngestApi {
    /** 同步接口只在 ANR 上传工作线程调用。 */
    @Headers("Content-Type: application/json")
    @POST("ingest/v1/batches")
    fun ingest(@Header("X-Schema-Version") schemaVersion: Int, @Body body: AnrBatchRequest): Call<AnrBatchResponse>
}

/** 单次请求的可判定传输结果。 */
internal sealed interface AnrSendResult {
    /** 成功解析的服务端批次响应。 */ data class Success(val response: AnrBatchResponse) : AnrSendResult
    /** HTTP 失败及重试建议。 */ data class HttpFailure(val status: Int, val retryable: Boolean,
        val retryAfterSeconds: Long? = null) : AnrSendResult
    /** 未取得有效响应。 */ data object Retry : AnrSendResult
}

/** 允许测试注入响应丢失和业务错误。 */
internal fun interface AnrBatchSender {
    /** 发送一个不可变事件批次。 */
    fun send(request: AnrBatchRequest): AnrSendResult
}

/** 将共享 Retrofit 会话适配为 ANR 批次发送。 */
internal class AnrHttpSender(
    /** 借用会话创建的接口。 */ private val api: AnrIngestApi,
    /** 公共协议版本。 */ private val schemaVersion: Int = 2,
) : AnrBatchSender {
    /** 请求、响应和失败日志均不输出诊断正文。 */
    override fun send(request: AnrBatchRequest): AnrSendResult = try {
        val response = api.ingest(schemaVersion, request).execute()
        if (response.code() == 200) {
            response.body()?.let { AnrSendResult.Success(it) } ?: AnrSendResult.Retry
        } else {
            /** 限制错误响应解析范围，不记录原始内容。 */
            val text = response.errorBody()?.charStream()?.use { reader ->
                val chars = CharArray(8 * 1024)
                val count = reader.read(chars)
                if (count < 0) "" else String(chars, 0, count)
            }
            val body = runCatching { GsonBuilder().create().fromJson(text, ErrorBody::class.java) }.getOrNull()
            val status = response.code()
            val retry = body?.retryable ?: (status == 408 || status == 429 || status in 500..599)
            val after = response.headers()["Retry-After"]?.trim()?.toLongOrNull() ?: body?.retryAfterSeconds
            AnrSendResult.HttpFailure(status, retry, after)
        }
    } catch (_: IOException) {
        AnrSendResult.Retry
    } catch (_: RuntimeException) {
        AnrSendResult.Retry
    }

    /** 常见服务端错误信封中的重试提示。 */
    private data class ErrorBody(val retryable: Boolean? = null, val retryAfterSeconds: Long? = null)
}

/** 保留未确认事件并执行公共批次确认和退避。 */
internal class AnrUploader(
    /** 本地冻结队列。 */ private val store: AnrCaptureStore,
    /** 当前会话目标摘要。 */ private val target: String,
    /** 实际 HTTP 发送器。 */ private val sender: AnrBatchSender,
    /** 墙上时间用于持久化退避。 */ private val clock: () -> Long = System::currentTimeMillis,
    /** 批次请求 ID。 */ private val requestId: () -> String = { UUID.randomUUID().toString() },
    /** 阶段日志不包含原始内容。 */ private val log: (String) -> Unit = {},
) {
    /** 每次最多处理有限数量，避免连续重试占满后台线程。 */
    fun flush() {
        store.expire(clock() - ANR_PENDING_RETENTION_MILLIS)
        repeat(MAX_BATCHES) {
            val record = store.eligible(target, clock(), 1).firstOrNull() ?: return
            val request = AnrBatchRequest(requestId(), listOf(record.event))
            when (val result = sender.send(request)) {
                is AnrSendResult.Success -> if (!handleSuccess(record, request, result.response)) return
                is AnrSendResult.HttpFailure -> {
                    if (result.retryable) retry(record, result.retryAfterSeconds)
                    else store.reject(target, record.event.eventId, "http_${result.status}")
                    return
                }
                AnrSendResult.Retry -> { retry(record, null); return }
            }
        }
    }

    /** 确认必须完整覆盖当前请求且错误能唯一定位，否则整项保留。 */
    private fun handleSuccess(record: AnrPendingRecord, request: AnrBatchRequest, response: AnrBatchResponse): Boolean {
        val accepted = response.accepted
        val rejected = response.rejected
        val duplicate = response.duplicate
        if (response.requestId != request.requestId || accepted == null || rejected == null || duplicate == null ||
            accepted < 0 || rejected < 0 || duplicate < 0 ||
            accepted.toLong() + rejected + duplicate != 1L
        ) { retry(record, response.retryAfterSeconds); return false }
        val rawErrors = response.errors ?: run { retry(record, response.retryAfterSeconds); return false }
        if (rawErrors.any { it == null }) { retry(record, response.retryAfterSeconds); return false }
        val errors = rawErrors.filterNotNull()
        if (errors.size != rejected ||
            errors.any { (it.index != null && it.index != 0) ||
                (it.eventId != null && it.eventId != record.event.eventId) ||
                (it.index == null && it.eventId == null) }
        ) { retry(record, response.retryAfterSeconds); return false }
        if (response.retryable || errors.any { it.retryable }) {
            retry(record, response.retryAfterSeconds)
            return false
        }
        if (rejected == 1) {
            store.reject(target, record.event.eventId, "server_rejected")
        } else {
            store.acknowledge(target, record.event.eventId)
        }
        log("ANR 批次确认已处理")
        return true
    }

    /** 使用服务端建议或有界指数退避。 */
    private fun retry(record: AnrPendingRecord, retryAfterSeconds: Long?) {
        val delay = retryAfterSeconds?.coerceIn(0, MAX_BACKOFF_MILLIS / 1_000)?.times(1_000)
            ?: (BASE_BACKOFF_MILLIS * (1L shl record.attempts.coerceIn(0, 7))).coerceAtMost(MAX_BACKOFF_MILLIS)
        store.retry(target, record.event.eventId, clock() + delay)
        log("ANR 批次等待重试")
    }

    /** 上传和保留期边界。 */
    private companion object {
        const val MAX_BATCHES = 5
        const val BASE_BACKOFF_MILLIS = 30_000L
        const val MAX_BACKOFF_MILLIS = 60L * 60 * 1_000
    }
}

/** 对规范地址和凭据计算目标摘要，不持久化凭据明文。 */
internal fun anrTargetFingerprint(session: TransportSession): String {
    /** Retrofit 已对基础地址执行 URL 规范化。 */
    val base = session.retrofit.baseUrl().toString()
    val input = "$base\n${session.config.appKey}"
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
