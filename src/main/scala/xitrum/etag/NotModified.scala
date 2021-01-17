package xitrum.etag

import java.text.SimpleDateFormat
import java.util.{Locale, TimeZone}

import io.netty.handler.codec.http.{HttpHeaderNames, HttpHeaderValues, FullHttpResponse}
import HttpHeaderNames._
import HttpHeaderValues._

object NotModified {
  private[this] val SECS_IN_A_YEAR = 365 * 24 * 60 * 60

  // SimpleDateFormat is locale dependent
  // Avoid the case when Xitrum is run on for example Japanese platform
  private[this] val RFC_2822 = {
    val ret = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    ret.setTimeZone(TimeZone.getTimeZone("GMT"))
    ret
  }

  def formatRfc2822(timestamp: Long): String = RFC_2822.format(timestamp)

  /**
   * Tells the browser to cache static files for a long time.
   * This works well even when this is a cluster of web servers behind a load balancer
   * because the URL created by urlForResource is in the form: resource?etag
   *
   * Don't worry that browsers do not pick up new files after you modified them,
   * see the doc about static files.
   *
   * Google recommends 1 year:
   * http://code.google.com/speed/page-speed/docs/caching.html
   *
   * Both Max-age and Expires header are set because IEs use Expires, not max-age:
   * http://mrcoles.com/blog/cookies-max-age-vs-expires/
   */
  def setClientCacheAggressively(response: FullHttpResponse): Unit = {
    if (!response.headers.contains(CACHE_CONTROL))
      response.headers.set(CACHE_CONTROL, "public, " + MAX_AGE + "=" + SECS_IN_A_YEAR)

    // CORS:
    // http://sockjs.github.com/sockjs-protocol/sockjs-protocol-0.3.3.html#section-7
    if (!response.headers.contains(ACCESS_CONTROL_MAX_AGE))
      response.headers.set(ACCESS_CONTROL_MAX_AGE, SECS_IN_A_YEAR)

    // Note that SECS_IN_A_YEAR * 1000 is different from SECS_IN_A_YEAR * 1000L
    // because of integer overflow!
    if (!response.headers.contains(EXPIRES))
      response.headers.set(EXPIRES, formatRfc2822(System.currentTimeMillis() + SECS_IN_A_YEAR * 1000L))
  }

  /**
   * Prevents client cache.
   * Note that "pragma: no-cache" is linked to requests, not responses:
   * http://palizine.plynt.com/issues/2008Jul/cache-control-attributes/
   */
  def setNoClientCache(response: FullHttpResponse): Unit = {
    // http://sockjs.github.com/sockjs-protocol/sockjs-protocol-0.3.html#section-11
    response.headers.remove(EXPIRES)
    response.headers.remove(LAST_MODIFIED)
    response.headers.set(CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
  }
}
