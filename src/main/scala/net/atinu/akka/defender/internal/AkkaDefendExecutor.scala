package net.atinu.akka.defender.internal

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.defend.DefendBatchingExecutor
import akka.pattern.CircuitBreakerOpenException
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor._
import net.atinu.akka.defender.internal.AkkaDefendExecutor.{ ClosingCircuitBreakerSucceed, ClosingCircuitBreakerFailed, TryCloseCircuitBreaker }
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Failure, Success, Try }

class AkkaDefendExecutor(val msgKey: DefendCommandKey, val cfg: MsgConfig, val dispatcherHolder: DispatcherHolder)
    extends Actor with ActorLogging with Stash {

  import akka.pattern.pipe

  import scala.concurrent.duration._

  val statsActor: ActorRef = statsActorForKey(msgKey)
  var stats: Option[CmdKeyStatsSnapshot] = None

  def receive = receiveClosed(isHalfOpen = false)

  def receiveClosed(isHalfOpen: Boolean): Receive = {
    case msg: DefendExecution[_] =>
      import context.dispatcher
      callAsync(msg, isHalfOpen) pipeTo sender()

    case msg: SyncDefendExecution[_] =>
      import context.dispatcher
      callSync(msg, isHalfOpen) pipeTo sender()

    case FallbackAction(promise, msg: DefendExecution[_]) =>
      fallbackFuture(promise, callAsync(msg, isHalfOpen))

    case FallbackAction(promise, msg: SyncDefendExecution[_]) =>
      fallbackFuture(promise, callSync(msg, isHalfOpen))

    case snap: CmdKeyStatsSnapshot =>
      val errorCount = snap.callStats.timeoutCount
      stats = Some(snap)
      if (errorCount >= cfg.cbConfig.maxFailures - 1) {
        openCircuitBreaker()
      }
  }

  def receiveOpen(end: Long): Receive = {
    case TryCloseCircuitBreaker =>
      context.become(receiveClosed(isHalfOpen = true))

    case msg: DefendExecution[_] =>
      import context.dispatcher
      callBreak(calcRemaining(end)) pipeTo sender()

    case msg: SyncDefendExecution[_] =>
      import context.dispatcher
      callBreak(calcRemaining(end)) pipeTo sender()

    case FallbackAction(promise, _) =>
      promise.completeWith(callBreak(calcRemaining(end)))

    case snap: CmdKeyStatsSnapshot => {
      stats = Some(snap)
    }
  }

  def receiveHalfOpen: Receive = {
    case ClosingCircuitBreakerFailed =>
      log.debug("circuit closed test call failed for {}", msgKey.name)
      openCircuitBreaker()
      unstashAll()
    case ClosingCircuitBreakerSucceed =>
      context.become(receiveClosed(isHalfOpen = false))
      unstashAll()
    case _ =>
      stash()
  }

  def openCircuitBreaker(): Unit = {
    import context.dispatcher
    log.debug("{} open circuit breaker for {}", msgKey.name, cfg.cbConfig.resetTimeout)
    context.system.scheduler.scheduleOnce(cfg.cbConfig.resetTimeout, self, TryCloseCircuitBreaker)
    context.become(receiveOpen(System.currentTimeMillis() + cfg.cbConfig.resetTimeout.toMillis))
  }

  def calcRemaining(end: Long) = {
    val r = end - System.currentTimeMillis()
    if (end > 0) r.millis
    else 0.millis
  }

  def fallbackFuture(promise: Promise[Any], res: Future[_]) =
    promise.completeWith(res)

  def callSync(msg: SyncDefendExecution[_], breakOnSingleFailure: Boolean): Future[Any] = {
    if (dispatcherHolder.isDefault) {
      log.warning("Use of default dispatcher for command {}, consider using a custom one", msg.cmdKey)
    }
    execFlow(msg, breakOnSingleFailure, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync(msg: DefendExecution[_], breakOnSingleFailure: Boolean): Future[Any] = {
    execFlow(msg, breakOnSingleFailure, msg.execute)
  }

  def execFlow(msg: NamedCommand[_], breakOnSingleFailure: Boolean, execute: => Future[Any]): Future[Any] = {
    val exec = callThrough(execute)
    updateCallStats(exec)
    if (breakOnSingleFailure) waitForApproval(exec)
    fallbackIfDefined(msg, exec)
  }

  def waitForApproval(exec: Future[Any]) = {
    log.debug("{} become half open", msgKey.name)
    context.become(receiveHalfOpen)
    import context.dispatcher
    exec.onComplete {
      case Success(v) =>
        self ! ClosingCircuitBreakerSucceed
      case Failure(e) =>
        self ! ClosingCircuitBreakerFailed
    }
  }

  // adapted based on the akka circuit breaker implementation
  def callThrough[T](body: ⇒ Future[T]): Future[T] = {

    def materialize[U](value: ⇒ Future[U]): Future[U] = try value catch { case NonFatal(t) ⇒ Future.failed(t) }

    if (cfg.cbConfig.callTimeout == Duration.Zero) {
      materialize(body)
    } else {
      val p = Promise[T]()

      implicit val ec = AkkaDefendExecutor.sameThreadExecutionContext

      val timeout = context.system.scheduler.scheduleOnce(cfg.cbConfig.callTimeout) {
        p tryCompleteWith AkkaDefendExecutor.timeoutFuture
      }

      materialize(body).onComplete { result ⇒
        p tryComplete result
        timeout.cancel
      }
      p.future
    }
  }

  def callBreak[T](remainingDuration: FiniteDuration): Future[T] =
    Promise.failed[T](new CircuitBreakerOpenException(remainingDuration)).future

  def updateCallStats(exec: Future[Any]): Unit = {
    import context.dispatcher
    val startTime = System.currentTimeMillis()
    exec.onComplete {
      case Success(_) =>
        val t = System.currentTimeMillis() - startTime
        statsActor ! ReportSuccCall(t)
      case Failure(v) =>
        val t = System.currentTimeMillis() - startTime
        val msg = v match {
          case e: TimeoutException => ReportTimeoutCall(t)
          case e: CircuitBreakerOpenException => ReportCircuitBreakerOpenCall
          case e => ReportErrorCall(t)
        }
        statsActor ! msg
    }
  }

  def fallbackIfDefined(msg: NamedCommand[_], exec: Future[Any]): Future[Any] = msg match {
    case static: StaticFallback[_] => exec.fallbackTo(Future.fromTry(Try(static.fallback)))
    case dynamic: CmdFallback[_] =>
      exec.fallbackTo {
        val fallbackPromise = Promise.apply[Any]()
        self ! FallbackAction(fallbackPromise, dynamic.fallback)
        fallbackPromise.future
      }
    case _ => exec
  }

  def statsActorForKey(cmdKey: DefendCommandKey) = {
    val cmdKeyName = cmdKey.name
    context.actorOf(AkkaDefendCmdKeyStatsActor.props(cmdKey), s"stats-$cmdKeyName")
  }

}

object AkkaDefendExecutor {

  def props(msgKey: DefendCommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) =
    Props(new AkkaDefendExecutor(msgKey, cfg, dispatcherHolder))

  private[internal] case object TryCloseCircuitBreaker
  private[internal] case object ClosingCircuitBreakerFailed
  private[internal] case object ClosingCircuitBreakerSucceed

  private[internal] object sameThreadExecutionContext extends ExecutionContext with DefendBatchingExecutor {
    override protected def unbatchedExecute(runnable: Runnable): Unit = runnable.run()
    override protected def resubmitOnBlock: Boolean = false // No point since we execute on same thread
    override def reportFailure(t: Throwable): Unit =
      throw new IllegalStateException("exception in sameThreadExecutionContext", t)
  }

  private val timeoutFuture = Future.failed(new TimeoutException("Circuit Breaker Timed out.") with NoStackTrace)
}
