package com.shanshui.performance.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.io.IOException
import java.util.zip.GZIPOutputStream

/** 仅为当前基础地址的 JSON 批次生成完整、有界的 gzip 正文。 */
internal class BatchGzipInterceptor(
    /** 基础地址解析出的唯一批次端点。 */
    private val endpoint: HttpUrl,
    /** 是否记录安全的传输摘要。 */
    private val enableLogging: Boolean,
    /** 可替换的日志出口，便于验证脱敏。 */
    private val logger: (String) -> Unit = { HttpLoggingInterceptor.Logger.DEFAULT.log(it) },
) : Interceptor {
    /** 在网络发送前压缩，准备失败以 IOException 交回原上传重试链路。 */
    override fun intercept(chain: Interceptor.Chain): Response {
        /** 本次业务请求。 */
        val request = chain.request()
        /** 原始 JSON 正文。 */
        val body = request.body
        if (request.url.scheme != endpoint.scheme || request.url.host != endpoint.host ||
            request.url.port != endpoint.port || request.url.encodedPath != endpoint.encodedPath ||
            request.method != "POST" || body?.contentType()?.let {
                it.type == "application" && it.subtype == "json"
            } != true
        ) return chain.proceed(request)
        /** 压缩准备起点。 */
        val started = System.nanoTime()
        /** 已编码正文不参与准备，网络失败由原网络层处理。 */
        val encodings = request.headers.values("Content-Encoding")
        if (encodings.size == 1 && encodings.single().trim().equals("gzip", true)) {
            return chain.proceed(request)
        }
        /** 完成编码的新请求；所有准备均在 proceed 之前完成。 */
        val prepared = try {
            if (encodings.isNotEmpty()) throw IOException("unsupported_batch_encoding")
            /** 当前单批原始内容，写入时即检查上限。 */
            val raw = Buffer()
            limited(raw, RAW_LIMIT).buffer().use { body.writeTo(it) }
            /** 完整 gzip 输出，关闭后包含 CRC 与长度尾部。 */
            val compressed = Buffer()
            GZIPOutputStream(limited(compressed, GZIP_LIMIT).buffer().outputStream()).use {
                raw.clone().inputStream().use { input -> input.copyTo(it) }
            }
            /** 实际线上字节。 */
            val bytes = compressed.readByteArray()
            if (enableLogging) logger("batch gzip prepared: rawBytes=${raw.size} gzipBytes=${bytes.size} elapsedNs=${System.nanoTime() - started}")
            request.newBuilder().removeHeader("Content-Length").removeHeader("Transfer-Encoding")
                .header("Content-Encoding", "gzip")
                .method(request.method, bytes.toRequestBody(body.contentType())).build()
        } catch (failure: IOException) {
            if (enableLogging) logger("batch gzip failed: stage=prepare type=${failure.javaClass.simpleName}")
            throw failure
        }
        return chain.proceed(prepared)
    }

    /** 创建在写入前检查累计字节的 Sink，避免缓冲无限增长。 */
    private fun limited(destination: Sink, limit: Long): Sink = object : ForwardingSink(destination) {
        /** 当前已写入字节数。 */
        private var written = 0L
        /** 检查下一次写入是否超过当前单批预算。 */
        override fun write(source: Buffer, byteCount: Long) {
            if (byteCount > limit - written) throw IOException("batch_buffer_limit")
            super.write(source, byteCount)
            written += byteCount
        }
    }

    /** 固定的单批内存防御边界，不改变领域批次和服务端线上限制。 */
    private companion object {
        /** SDK 允许的最大原始单批。 */
        const val RAW_LIMIT = 1024L * 1024
        /** 包含 gzip 尾部的输出缓冲上限。 */
        const val GZIP_LIMIT = 2L * 1024 * 1024
    }
}
