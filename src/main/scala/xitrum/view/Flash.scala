package xitrum.view

import xitrum.Action

import scala.xml.Node

object FlashRenderer {
  val FLASH_KEY = "_flash"
}

trait FlashRenderer {
  this: Action =>

  import FlashRenderer._

  /** @see jsFlash(msg). */
  def flash(msg: Any): Unit = {
    session(FLASH_KEY) = msg
  }

  /**
   * Returns the current content in flash, and clears the flash.
   */
  def flash(): String = {
    sessiono(FLASH_KEY) match {
      case None =>
        ""

      case Some(msg) =>
        session.remove(FLASH_KEY)
        msg
    }
  }

  //----------------------------------------------------------------------------

  protected def jsFlashCall(msg: Any): String = "xitrum.flash(\"" + jsEscape(msg) + "\")"

  /**
   * For web 2.0 style application.
   * Used in application layout to display the flash message right after a view is loaded.
   */
  def jsRenderFlash(msg: Any): Unit = {
    val js = jsFlashCall(msg)
    jsAddToView(js)
  }

  /**
   * Like jsRenderFlash(msg), but uses the current flash.
   */
  def jsRenderFlash(): Unit = {
    val msg = flash()
    if (msg.nonEmpty) jsRenderFlash(msg)
  }

  lazy val xitrumCss: Node =
    <link href={webJarsUrl(s"xitrum/${xitrum.version}/xitrum.css")} type="text/css" rel="stylesheet" media="all" />
}

trait FlashResponder {
  this: Action =>

  /**
    * For web 2.0 style application.
    * Used in Ajax request handling to respond a message and have the browser
    * render it to the flash area right away.
    */
  def jsRespondFlash(msg: Any): Unit = {
    val js = jsFlashCall(msg)
    jsRespond(js)
  }
}
