package xitrum

import scala.util.control.NonFatal
import akka.actor.Actor

import io.netty.channel.{ChannelFuture, ChannelFutureListener}
import io.netty.handler.codec.http.{HttpMethod, HttpResponseStatus}

import xitrum.action._
import xitrum.exception.{InvalidAntiCsrfToken, InvalidInput, MissingParam, SessionExpired}
import xitrum.handler.{AccessLog, HandlerEnv}
import xitrum.handler.inbound.{Dispatcher, NoPipelining}
import xitrum.handler.outbound.ResponseCacher
import xitrum.scope.request.RequestEnv
import xitrum.scope.session.{Csrf, SessionEnv}
import xitrum.view.{Renderer, Responder}

/**
 * Action is designed to be separated from ActorAction. ActorAction extends
 * Action. An ActorAction can pass its Action things outside without violating
 * the principle of Actor: Do not leak Actor internals to outside.
 */
trait Action extends RequestEnv
  with SessionEnv
  with Log
  with Net
  with Filter
  with BasicAuth
  with Redirect
  with Url
  with Renderer
  with Responder
  with I18n
{
  implicit val currentAction = Action.this

  /**
   * Called when the HTTP request comes in.
   * Actions have to implement this method.
   */
  def execute()

  def addConnectionClosedListener(listener: => Unit) {
    channel.closeFuture.addListener(new ChannelFutureListener {
      def operationComplete(future: ChannelFuture) { listener }
    })
  }

  //----------------------------------------------------------------------------

  def dispatchWithFailsafe() {
    val beginTimestamp = System.currentTimeMillis()
    val route          = handlerEnv.route
    val cacheSecs      = if (route == null) 0 else route.cacheSecs
    var hit            = false

    try {
      // Check for CSRF (CSRF has been checked if "postback" is true)
      if ((request.getMethod == HttpMethod.POST ||
           request.getMethod == HttpMethod.PUT ||
           request.getMethod == HttpMethod.DELETE) &&
          !isInstanceOf[SkipCsrfCheck] &&
          !Csrf.isValidToken(Action.this)) throw new InvalidAntiCsrfToken

      // Before filters:
      // When not passed, the before filters must explicitly respond to client,
      // with appropriate response status code, error description etc.
      // This logic is app-specific, Xitrum cannot does it for the app.

      if (cacheSecs > 0) {     // Page cache
        hit = tryCache {
          val passed = callBeforeFilters()
          if (passed) callExecuteWrappedInAroundFiltersThenAfterFilters()
        }
      } else {
        val passed = callBeforeFilters()
        if (passed) {
          if (cacheSecs < 0)  // Action cache
            hit = tryCache { callExecuteWrappedInAroundFiltersThenAfterFilters() }
          else                // No cache
            callExecuteWrappedInAroundFiltersThenAfterFilters()
        }
      }

      if (!forwarding) AccessLog.logActionAccess(this, beginTimestamp, cacheSecs, hit)
    } catch {
      case NonFatal(e) =>
        if (forwarding) {
          log.warn("Error", e)
          return
        }

        // End timestamp
        val t2 = System.currentTimeMillis()

        // These exceptions are special cases:
        // We know that the exception is caused by the client (bad request)
        if (e.isInstanceOf[SessionExpired] || e.isInstanceOf[InvalidAntiCsrfToken] || e.isInstanceOf[MissingParam] || e.isInstanceOf[InvalidInput]) {
          response.setStatus(HttpResponseStatus.BAD_REQUEST)
          val msg = if (e.isInstanceOf[SessionExpired] || e.isInstanceOf[InvalidAntiCsrfToken]) {
            session.clear()
            "Session expired. Please refresh your browser."
          } else if (e.isInstanceOf[MissingParam]) {
            val mp  = e.asInstanceOf[MissingParam]
            "Missing param: " + mp.key
          } else {
            val ve = e.asInstanceOf[InvalidInput]
            "Validation error: " + ve.message
          }

          if (isAjax)
            jsRespond("alert(\"" + jsEscape(msg) + "\")")
          else
            respondText(msg)

          AccessLog.logActionAccess(Action.this, beginTimestamp, 0, false)
        } else {
          response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR)
          if (Config.productionMode) {
            Config.routes.error500 match {
              case None =>
                respondDefault500Page()

              case Some(error500) =>
                if (error500 == getClass) {
                  respondDefault500Page()
                } else {
                  response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                  Dispatcher.dispatch(error500, handlerEnv)
                }
            }
          } else {
            val errorMsg = e.toString + "\n\n" + e.getStackTraceString
            if (isAjax)
              jsRespond("alert(\"" + jsEscape(errorMsg) + "\")")
            else
              respondText(errorMsg)
          }

          AccessLog.logActionAccess(Action.this, beginTimestamp, 0, false, e)
        }
    }
  }

  /** @return true if the cache was hit */
  private def tryCache(f: => Unit): Boolean = {
    ResponseCacher.getCachedResponse(handlerEnv) match {
      case None =>
        f  // Execute f
        false

      case Some(response) =>
        val future = channel.writeAndFlush(response)
        handlerEnv.release()
        NoPipelining.if_keepAliveRequest_then_resumeReading_else_closeOnComplete(request, channel, future)
        true
    }
  }

  private def callExecuteWrappedInAroundFiltersThenAfterFilters() {
    callExecuteWrappedInAroundFilters()
    callAfterFilters()
  }
}
