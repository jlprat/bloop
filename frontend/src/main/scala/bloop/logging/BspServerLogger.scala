package bloop.logging

import java.util.concurrent.atomic.AtomicInteger

import bloop.engine.State
import bloop.reporter.Problem
import sbt.internal.inc.bloop.ZincInternals
import xsbti.Severity

import scala.meta.jsonrpc.JsonRpcClient
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.endpoints.{Build, BuildTarget}

/**
 * Creates a logger that will forward all the messages to the underlying bsp client.
 * It does so via the replication of the `build/logMessage` LSP functionality.
 */
final class BspServerLogger private (
    override val name: String,
    underlying: Logger,
    implicit val client: JsonRpcClient,
    ansiSupported: Boolean
) extends Logger
    with ScribeAdapter {

  override def debugFilter: DebugFilter = underlying.debugFilter

  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger =
    new BspServerLogger(name, underlying.asDiscrete, client, ansiSupported)
  override def asVerbose: Logger =
    new BspServerLogger(name, underlying.asVerbose, client, ansiSupported)

  override def ansiCodesSupported: Boolean = ansiSupported || underlying.ansiCodesSupported()

  override private[logging] def printDebug(msg: String): Unit = underlying.printDebug(msg)
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit =
    if (debugFilter.isEnabledFor(ctx)) printDebug(msg)

  override def trace(t: Throwable): Unit = underlying.trace(t)

  override def error(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Error, None, None, msg))
    ()
  }

  override def warn(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Warning, None, None, msg))
    ()
  }

  override def info(msg: String): Unit = {
    Build.logMessage.notify(bsp.LogMessageParams(bsp.MessageType.Info, None, None, msg))
    ()
  }

  def diagnostic(problem: Problem): Unit = {
    import sbt.util.InterfaceUtil.toOption
    val message = problem.message
    val problemPos = problem.position
    val sourceFile = toOption(problemPos.sourceFile())

    (problemPos, sourceFile) match {
      case (ZincInternals.ZincExistsPos(startLine, startColumn), Some(file)) =>
        val pos = problem.position match {
          case ZincInternals.ZincRangePos(endLine, endColumn) =>
            val start = bsp.Position(startLine, startColumn)
            val end = bsp.Position(endLine, endColumn)
            bsp.Range(start, end)
          case _ =>
            val pos = bsp.Position(startLine, startColumn)
            bsp.Range(pos, pos)
        }

        val severity = problem.severity match {
          case Severity.Error => bsp.DiagnosticSeverity.Error
          case Severity.Warn => bsp.DiagnosticSeverity.Warning
          case Severity.Info => bsp.DiagnosticSeverity.Information
        }

        val uri = bsp.Uri(file.toURI)
        val diagnostic = bsp.Diagnostic(pos, Some(severity), None, None, message, None)
        val diagnostics = bsp.PublishDiagnosticsParams(uri, None, List(diagnostic))
        Build.publishDiagnostics.notify(diagnostics)
      case _ =>
        problem.severity match {
          case Severity.Error => error(message)
          case Severity.Warn => warn(message)
          case Severity.Info => info(message)
        }
    }
    ()
  }

  def publishBspReport(uri: bsp.Uri, problems: Seq[Problem]): Unit = {
    val errors = problems.count(_.severity == Severity.Error)
    val warnings = problems.count(_.severity == Severity.Warn)
    BuildTarget.compileReport.notify(
      bsp.CompileReport(bsp.BuildTargetIdentifier(uri), None, errors, warnings, None)
    )
    ()
  }
}

object BspServerLogger {
  private[bloop] final val counter: AtomicInteger = new AtomicInteger(0)

  def apply(state: State, client: JsonRpcClient, ansiCodesSupported: Boolean): BspServerLogger = {
    val name: String = s"bsp-logger-${BspServerLogger.counter.incrementAndGet()}"
    new BspServerLogger(name, state.logger, client, ansiCodesSupported)
  }
}
