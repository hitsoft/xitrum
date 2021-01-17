package xitrum.handler.inbound

import java.io.File
import scala.collection.mutable.{Map => MMap}
import scala.util.control.NonFatal

import io.netty.buffer.Unpooled
import io.netty.channel.{SimpleChannelInboundHandler, ChannelHandlerContext}
import io.netty.handler.codec.http.{
  HttpRequest, FullHttpRequest, FullHttpResponse, DefaultFullHttpRequest, DefaultFullHttpResponse,
  HttpMethod, HttpHeaderNames, HttpHeaderValues, HttpContent, HttpObject, LastHttpContent, HttpResponseStatus, HttpUtil, HttpVersion
}
import io.netty.handler.codec.http.multipart.{
  Attribute, DiskAttribute,
  FileUpload, DiskFileUpload,
  DefaultHttpDataFactory, HttpPostRequestDecoder, InterfaceHttpData, HttpData
}
import InterfaceHttpData.HttpDataType
import HttpMethod._

import xitrum.{Config, Log}
import xitrum.handler.{HandlerEnv, NoRealPipelining}
import xitrum.scope.request.{FileUploadParams, Params}

import org.json4s._
import org.json4s.jackson.JsonMethods

object Request2Env {
  // This directory must exist otherwise Netty will throw:
  // java.io.IOException: No such file or directory
  val uploadDir = new File(Config.xitrum.tmpDir, "upload")
  if (!uploadDir.exists) uploadDir.mkdir()

  DiskAttribute.baseDirectory  = uploadDir.getAbsolutePath
  DiskFileUpload.baseDirectory = uploadDir.getAbsolutePath

  // "true" will cause out of memory when there are too many temporary files
  // https://github.com/xitrum-framework/xitrum/issues/634
  //
  // Temporary files of a request will be deleted immediately after the response is responded
  DiskAttribute.deleteOnExitTemporaryFile  = false
  DiskFileUpload.deleteOnExitTemporaryFile = false

  // Save a field to disk if its size exceeds maxSizeInBytesOfUploadMem;
  // creating factory should be after the above for the factory to take effect of the settings
  val factory = new DefaultHttpDataFactory(Config.xitrum.request.maxSizeInBytesOfUploadMem)
  factory.setMaxLimit(Config.xitrum.request.maxSizeInBytes)
}

/**
 * This handler converts request with its content body (if any, e.g. in case of
 * file upload) to HandlerEnv, and send it upstream to the next handler.
 */
class Request2Env extends SimpleChannelInboundHandler[HttpObject] {
  // Based on the file upload example in Netty.

  import Request2Env._

  // Will be reset to null after being sent upstream to the next handler
  private[this] var env: HandlerEnv  = _

  // For checking if decoded body is too big, bigger than Config.xitrum.request.maxSizeInBytes;
  // raw request body size is checked separately (see factory.setMaxLimit above)
  private[this] var bodyBytesDecoded = 0L

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    // In case the connection is closed when the request is not fully received,
    // thus env is initialized but not sent upstream to the next handler
    if (env != null) {
      env.release()
      env = null
    }
  }

  override def channelRead0(ctx: ChannelHandlerContext, msg: HttpObject): Unit = {
    val decRet = msg.decoderResult
    if (decRet.isFailure) {
      Log.debug("Could not decode request", decRet.cause)
      BadClientSilencer.respond400(ctx.channel, "Could not decode request")
      return
    }

    try {
      // For each request:
      // - HttpRequest (or subclass) will come first
      // - Other HttpObjects will follow
      // - LastHttpContent (or subclass) will come last
      msg match {
        case request: HttpRequest =>
          // http://www.w3.org/Protocols/rfc2616/rfc2616-sec8.html
          // curl can send "100-continue" header:
          // curl -v -X POST -F "a=b" http://server/
          if (HttpUtil.is100ContinueExpected(request)) {
            // This request only contains headers, write response and flush
            // immediately so that the client sends the rest of the request as
            // soon as possible
            val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)
            ctx.channel.writeAndFlush(response)
          }

          handleHttpRequestHead(ctx, request)

        case _ =>
          // HttpContent can be LastHttpContent (see below)
          if (env != null && msg.isInstanceOf[HttpContent])
            handleHttpRequestContent(ctx, msg.asInstanceOf[HttpContent])

          // LastHttpContent is a HttpContent.
          // env may be set to null at handleHttpRequestContent above, when
          // closeOnBigRequest is called.
          if (env != null && msg.isInstanceOf[LastHttpContent]) {
            if (isAPPLICATION_JSON(env.request)) parseTextParamsFromJson(env)
            sendUpstream(ctx)
          }
      }
    } catch {
      case NonFatal(e) =>
        Log.debug(s"Could not parse content body of request: $msg", e)
        BadClientSilencer.respond400(ctx.channel, "Could not parse content body of request")
    }
  }

  //----------------------------------------------------------------------------

  private def handleHttpRequestHead(ctx: ChannelHandlerContext, request: HttpRequest): Unit = {
    // See DefaultHttpChannelInitializer
    // This is the first Xitrum handler, log the request
    Log.trace(request.toString)

    // Clean previous files if any;
    // one connection may be used to send multiple requests,
    // so one handler instance may be used to handle multiple requests
    if (env != null) env.release()

    val method       = request.method
    val bodyToDecode = method == POST || method == PUT || method == PATCH
    var responded400 = false
    val bodyDecoder  =
      if (bodyToDecode && isAPPLICATION_X_WWW_FORM_URLENCODED_or_MULTIPART_FORM_DATA(request)){
        try {
          new HttpPostRequestDecoder(factory, request, Config.xitrum.request.charset)
        } catch {
          // Another exception is IncompatibleDataDecoderException, which means the
          // request is valid, just no need to decode (see the check above)
          case e: HttpPostRequestDecoder.ErrorDataDecoderException =>
            Log.debug("Could not parse content body of request", e)
            BadClientSilencer.respond400(ctx.channel, "Could not parse content body of request")
            responded400 = true
            null
        }
      } else {
        null
      }

    // Only initialize env when needed
    if (!responded400) {
      env                = new HandlerEnv
      env.channel        = ctx.channel
      env.bodyTextParams = MMap.empty[String, Seq[String]]
      env.bodyFileParams = MMap.empty[String, Seq[FileUpload]]
      env.request        = createEmptyFullHttpRequest(request)
      env.response       = createEmptyFullResponse(request)
      env.bodyDecoder    = bodyDecoder
      bodyBytesDecoded   = 0
    }
  }

  private def handleHttpRequestContent(ctx: ChannelHandlerContext, content: HttpContent): Unit = {
    // To save memory, only set env.request.content when env.bodyDecoder is not in action
    if (env.bodyDecoder == null) {
      val body   = content.content
      val length = body.readableBytes
      if (bodyBytesDecoded + length <= Config.xitrum.request.maxSizeInBytes) {
        env.request.content.writeBytes(body)
        bodyBytesDecoded += length
      } else {
        closeOnBigRequest(ctx)
      }
    } else {
      // Raw request body size will be checked (see factory.setMaxLimit above)
      env.bodyDecoder.offer(content)

      // Decoded request body size will be checked
      if (!readHttpDataChunkByChunk()) closeOnBigRequest(ctx)
    }
  }

  //----------------------------------------------------------------------------

  private def createEmptyFullHttpRequest(request: HttpRequest): FullHttpRequest = {
    val ret = new DefaultFullHttpRequest(request.protocolVersion, request.method, request.uri)
    ret.headers.set(request.headers)
    ret
  }

  private def createEmptyFullResponse(request: HttpRequest): FullHttpResponse = {
    // https://github.com/netty/netty/issues/2137
    val compositeBuf = Unpooled.compositeBuffer()

    // In HTTP 1.1 all connections are considered persistent unless declared otherwise
    // http://en.wikipedia.org/wiki/HTTP_persistent_connection
    val ret = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, compositeBuf)

    // Unless the Connection: keep-alive header is present in the HTTP response,
    // apache benchmark (ab) hangs on keep alive connections
    // https://github.com/veebs/netty/commit/64f529945282e41eb475952fde382f234da8eec7
    if (HttpUtil.isKeepAlive(request))
      ret.headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)

    ret
  }

  private def isAPPLICATION_X_WWW_FORM_URLENCODED_or_MULTIPART_FORM_DATA(request: HttpRequest): Boolean = {
    val requestContentType = request.headers.get(HttpHeaderNames.CONTENT_TYPE)
    if (requestContentType == null) return false

    val requestContentTypeLowerCase = requestContentType.toLowerCase
    requestContentTypeLowerCase.startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString) ||
    requestContentTypeLowerCase.startsWith(HttpHeaderValues.MULTIPART_FORM_DATA.toString)
  }

  private def readHttpDataChunkByChunk(): Boolean = {
    try {
      var sizeOk = true
      while (sizeOk && env.bodyDecoder.hasNext) {
        val data = env.bodyDecoder.next()
        if (data != null) {
          sizeOk = checkHttpDataSize(data)
          if (sizeOk) putDataToEnv(data)
        }
      }
      sizeOk
    } catch {
      case _: HttpPostRequestDecoder.EndOfDataDecoderException =>
        true
    }
  }

  private def sanitizeFileUploadFilename(fileUpload: FileUpload): Unit = {
    val filename1 = fileUpload.getFilename
    val filename2 = filename1.split('/').last.split('\\').last.trim.replaceAll("^\\.+", "")
    val filename3 = if (filename2.isEmpty) "filename" else filename2
    fileUpload.setFilename(filename3)
  }

  private def putOrAppendString(map: Params, key: String, value: String): Unit = {
    if (!map.contains(key)) {
      map(key) = Seq(value)
    } else {
      val values = map(key)
      map(key) = values :+ value
    }
  }

  private def putOrAppendFileUpload(map: FileUploadParams, key: String, value: FileUpload): Unit = {
    if (!map.contains(key)) {
      map(key) = Seq(value)
    } else {
      val values = map(key)
      map(key) = values :+ value
    }
  }

  /** @return true if OK */
  private def checkHttpDataSize(data: InterfaceHttpData): Boolean = {
    val hd = data.asInstanceOf[HttpData]
    bodyBytesDecoded + hd.length <= Config.xitrum.request.maxSizeInBytes
  }

  private def putDataToEnv(data: InterfaceHttpData): Unit = {
    val dataType = data.getHttpDataType
    if (dataType == HttpDataType.Attribute) {
      val attribute = data.asInstanceOf[Attribute]
      val name      = attribute.getName
      val value     = attribute.getValue
      putOrAppendString(env.bodyTextParams, name, value)
      bodyBytesDecoded += attribute.length
    } else if (dataType == HttpDataType.FileUpload) {
      // Do not skip empty file
      // https://github.com/xitrum-framework/xitrum/issues/463
      val fileUpload = data.asInstanceOf[FileUpload]
      if (fileUpload.isCompleted) {
        val name   = fileUpload.getName
        val length = fileUpload.length
        sanitizeFileUploadFilename(fileUpload)
        putOrAppendFileUpload(env.bodyFileParams, name, fileUpload)
        bodyBytesDecoded += length
      }
    }
  }

  private def closeOnBigRequest(ctx: ChannelHandlerContext): Unit = {
    Log.debug("Request content body is too big, see xitrum.request.maxSizeInMB in xitrum.conf")
    BadClientSilencer.respond400(ctx.channel, "Request content body is too big. Limit: " + Config.xitrum.request.config.getLong("maxSizeInMB") + " bytes")

    // Mark that closeOnBigRequest has been called.
    // See the check for LastHttpContent above.
    env.release()
    env = null
  }

  private def sendUpstream(ctx: ChannelHandlerContext): Unit = {
    // NoRealPipelining.resumeReading should be called when the response has been sent
    //
    // PITFALL:
    // If this line is after the line "ctx.fireChannelRead(env)" (right below),
    // this order may happen: resumeReading -> pauseReading
    //
    // We want: pauseReading -> resumeReading
    NoRealPipelining.pauseReading(ctx.channel)

    ctx.fireChannelRead(env)

    // Reset for the next request on this same connection (e.g. keep alive)
    env               = null
    bodyBytesDecoded = 0
  }

  private def isAPPLICATION_JSON(request: HttpRequest): Boolean = {
    val requestContentType = request.headers.get(HttpHeaderNames.CONTENT_TYPE)
    if (requestContentType == null) return false

    val requestContentTypeLowerCase = requestContentType.toLowerCase
    requestContentTypeLowerCase.startsWith("application/json")
  }

  /**
   * Only parses one level.
   * Should use requestContentJValue directly for more advanced uses.
   */
  private def parseTextParamsFromJson(env: HandlerEnv): Unit = {
    env.requestContentJValue match {
      case JObject(fields) =>
        fields.foreach {
          case JField(name, JArray(values)) =>
            values.foreach { value =>
              putOrAppendString(env.bodyTextParams, name, jValue2String(value))
            }

          case JField(name, value) =>
            putOrAppendString(env.bodyTextParams, name, jValue2String(value))

          case _ =>
            // Other cases not supported
        }

      case _ => // Nothing to do
    }
  }

  private def jValue2String(value: JValue) = value match {
    case JNull | JNothing => "null"
    case JString(v)       => v
    case JInt(v)          => v.toString
    case JDouble(v)       => v.toString
    case JDecimal(v)      => v.toString
    case JBool(v)         => v.toString
    case v                => JsonMethods.compact(v)
  }
}
