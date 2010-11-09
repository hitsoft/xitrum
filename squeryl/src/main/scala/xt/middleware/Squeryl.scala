package xt.middleware

import xt._

import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse}

import org.squeryl.{SessionFactory, Session => SSession}
import org.squeryl.adapters.{PostgreSqlAdapter, MySQLAdapter, OracleAdapter}
import org.squeryl.PrimitiveTypeMode._

import com.mchange.v2.c3p0.ComboPooledDataSource

object Squeryl extends Logger {
  // Run f in a transaction with logger. The connection will be closed here after
  // the transaction.
  //
  // Do not use inTransaction because we want the session to be unbound:
  // http://groups.google.com/group/squeryl/browse_thread/thread/e7fe581f7f5f61f9
  def tl(f: => Unit) {
    transaction {
      org.squeryl.Session.currentSession.setLogger(s => logger.debug((s)))
      f
    }
  }

  def setupDB {
    val dataSource  = new ComboPooledDataSource
    val driverClass = dataSource.getDriverClass
    val adapter     = guessAdapter(driverClass)

    val concreteFactory = () => SSession.create(dataSource.getConnection, adapter)
    SessionFactory.concreteFactory = Some(concreteFactory)
  }

  //----------------------------------------------------------------------------

  /**
   * driverClass: org.postgresql.Driver or com.mysql.jdbc.Driver etc.
   */
  private def guessAdapter(driverClass: String) = {
    val lower = driverClass.toLowerCase
    if (lower.indexOf("mysql") != -1) {
      new MySQLAdapter
    } else if (lower.indexOf("oracle") != -1) {
      new OracleAdapter
    } else if (lower.indexOf("postgresql") != -1) {
      new PostgreSqlAdapter
    } else null
  }
}