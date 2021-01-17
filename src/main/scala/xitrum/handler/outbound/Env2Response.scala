package xitrum.handler.outbound

import io.netty.channel.{ChannelHandler, ChannelHandlerContext, ChannelOutboundHandlerAdapter, ChannelPromise}
import io.netty.handler.codec.http.{DefaultHttpResponse, HttpHeaderNames, HttpMethod, HttpRequest, FullHttpResponse, HttpResponseStatus, HttpUtil, HttpVersion}
import ChannelHandler.Sharable

import xitrum.{Config, Log}
import xitrum.etag.Etag
import xitrum.handler.HandlerEnv
import xitrum.util.{ByteBufUtil, Gzip}

@Sharable
class Env2Response extends ChannelOutboundHandlerAdapter {
  override def write(ctx: ChannelHandlerContext, msg: Object, promise: ChannelPromise): Unit = {
    if (!msg.isInstanceOf[HandlerEnv]) {
      ctx.write(msg, promise)
      return
    }

    val env      = msg.asInstanceOf[HandlerEnv]
    val request  = env.request
    val response = env.response

    val xsendfile = XSendFile.isHeaderSet(response)
    val chunked   = HttpUtil.isTransferEncodingChunked(response)

    if (xsendfile) XSendFile.removeHeaders(response)

    // For HEAD or OPTIONS response, Content-Length header may be > 0 even when
    // the content is empty (see below)
    if (!chunked && !HttpUtil.isContentLengthSet(response))
      HttpUtil.setContentLength(response, response.content.readableBytes)

    if ((request.method == HttpMethod.HEAD || request.method == HttpMethod.OPTIONS) && response.status == HttpResponseStatus.OK)
      // http://stackoverflow.com/questions/3854842/content-length-header-with-head-requests
      response.content.clear()
    else if (!tryEtag(request, response))
      Gzip.tryCompressBigTextualResponse(Gzip.isAccepted(request), response, needBytes = false)

    // The status may be set to NOT_MODIFIED by tryEtag above
    val notModified = response.status == HttpResponseStatus.NOT_MODIFIED

    // 304 responses should not include Content-Type or Content-Length
    // http://sockjs.github.com/sockjs-protocol/sockjs-protocol-0.3.3.html#section-25
    // https://groups.google.com/forum/#!topic/python-tornado/-P_enYKAwrY
    if (notModified || chunked) response.headers.remove(HttpHeaderNames.CONTENT_LENGTH)
    if (notModified)            response.headers.remove(HttpHeaderNames.CONTENT_TYPE)

    // For the following cases, we can't just send "response" because it's a
    // FullHttpResponse, we need to send HttpResponse:
    // * xsendfile, this is "Write the initial line and the header"; the file
    //   body will be sent by XSendFile handler
    // * chunked response
    if (xsendfile || chunked) {
      val onlyHeaders = new DefaultHttpResponse(response.protocolVersion, response.status)
      onlyHeaders.headers.set(response.headers)

      // TRANSFER_ENCODING header is not allowed in HTTP/1.0 response:
      // http://sockjs.github.com/sockjs-protocol/sockjs-protocol-0.3.3.html#section-165
      //
      // The header in the original response is a mark telling the response is chunked.
      // It should not be removed from the original response.
      if (request.protocolVersion.compareTo(HttpVersion.HTTP_1_0) == 0)
        HttpUtil.setTransferEncodingChunked(onlyHeaders, false)

      ctx.write(onlyHeaders, promise)
    } else {
      // Need to retain because response will be released when env.release() is called below
      ctx.write(response.retain(), promise)
    }

    if (ResponseCacher.shouldCache(env)) ResponseCacher.cacheResponse(env)

    env.release()

    // See DefaultHttpChannelInitializer
    // This is the last Xitrum handler, log the response
    Log.trace(response.toString)

    // Keep alive, channel reading resuming/closing etc. are handled
    // by the code that sends the response (Responder#respond)
  }

  //----------------------------------------------------------------------------

  /**
   * This does not make the server faster, but decreases the response transmission
   * time through the network to the browser.
   *
   * Only effective for non-empty non-async dynamic response,
   * e.g. not for static file (has alredy been handled and does not go through
   * this handler) or X-SendFile response (empty dynamic response).
   *
   * If HttpUtil.getContentLength(response) != response.content.readableBytes,
   * it is because the response is sent in async mode.
   *
   * @return true if the NO_MODIFIED response is set by this method
   */
  private def tryEtag(request: HttpRequest, response: FullHttpResponse): Boolean = {
    if (response.status == HttpResponseStatus.NOT_MODIFIED)
      return true

    if (response.status != HttpResponseStatus.OK)
      return false

    if (response.headers.contains(HttpHeaderNames.CACHE_CONTROL) &&
      response.headers.get(HttpHeaderNames.CACHE_CONTROL).equalsIgnoreCase("no-cache"))
      return false

    val contentLengthInHeader = HttpUtil.getContentLength(response, 0)
    val byteBuf               = response.content
    if (contentLengthInHeader == 0 || contentLengthInHeader != byteBuf.readableBytes) return false

    // No need to calculate ETag if it has been set, e.g. by the controller
    val etag1 = response.headers.get(HttpHeaderNames.ETAG)
    if (etag1 != null) {
      compareAndSetETag(request, response, etag1)
    } else {
      // It's not useful to calculate ETag for big response
      if (byteBuf.readableBytes > Config.xitrum.staticFile.maxSizeInBytesOfCachedFiles) return false

      val etag2 = Etag.forBytes(ByteBufUtil.toBytes(byteBuf))
      compareAndSetETag(request, response, etag2)
    }
  }

  private def compareAndSetETag(request: HttpRequest, response: FullHttpResponse, etag: String): Boolean = {
    if (Etag.areEtagsIdentical(request, etag)) {
      response.setStatus(HttpResponseStatus.NOT_MODIFIED)
      response.content.clear()
      true
    } else {
      Etag.set(response, etag)
      false
    }
  }
}
