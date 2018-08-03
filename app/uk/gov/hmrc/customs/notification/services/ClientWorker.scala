/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.customs.notification.services

import java.math.MathContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import com.google.inject.ImplementedBy
import org.joda.time.Duration
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.domain.{ClientNotification, ClientSubscriptionId}
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.logMsgPrefix
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockOwnerId, LockRepo}
import uk.gov.hmrc.http.HeaderCarrier

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DurationDouble, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.control.{ControlThrowable, NonFatal}

@ImplementedBy(classOf[ClientWorkerImpl])
trait ClientWorker {

  def processNotificationsFor(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId, lockDuration: Duration): Future[Unit]
}


/*
TODO:
- Do we need to also call isLocked?
Questions
- I still have concerns with blocking code inside a FUTURE
- I think if we have many concurrent CSIDs then with blocking code inside a FUTURE we may exhaust thread pool
  - https://stackoverflow.com/questions/15950998/futures-for-blocking-calls-in-scala
  - we may have to take responsibility of tuning thread pool with upper limit to prevent exhaustion

 */
@Singleton
class ClientWorkerImpl @Inject()(
                                  actorSystem: ActorSystem,
                                  repo: ClientNotificationRepo,
                                  callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                                  push: PushClientNotificationService,
                                  pull: PullClientNotificationService,
                                  lockRepo: LockRepo,
                                  logger: NotificationLogger
                                ) extends ClientWorker {

  private case class PushProcessingException(msg: String) extends RuntimeException(msg)
  private case class PullProcessingException(msg: String) extends RuntimeException(msg)
  // ControlThrowable is a marker trait that ensures this is treated as a Fatal exception
  private case class ExitOuterLoopException(msg: String) extends RuntimeException(msg) with ControlThrowable

  // TODO: read this value from HTTP VERBS config and add 10%
  private val awaitApiCallDuration = 25 second

  protected val loopIncrementToLog = 1000

  override def processNotificationsFor(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId, lockDuration: org.joda.time.Duration): Future[Unit] = {
    //implicit HeaderCarrier required for ApiSubscriptionFieldsConnector
    //however looking at api-subscription-fields service I do not think it is required so keep new HeaderCarrier() for now
    implicit val hc = HeaderCarrier()
    implicit val refreshLockFailed: AtomicBoolean = new AtomicBoolean(false)
    val refreshDuration = ninetyPercentOf(lockDuration)
    val timer = actorSystem.scheduler.schedule(initialDelay = refreshDuration, interval = refreshDuration, new Runnable {
      override def run(): Unit = {
        refreshLock(csid, lockOwnerId, lockDuration)
      }
    })

    // cleanup timer
    val eventuallyProcess = process(csid, lockOwnerId)
    eventuallyProcess.onComplete { _ => // always cancel timer ie for both Success and Failure cases
      logger.debug("about to cancel timer")
      val cancelled = timer.cancel()
      logger.debug(s"timer cancelled=$cancelled, timer.isCancelled=${timer.isCancelled}")
    }

    eventuallyProcess
  }

  private def ninetyPercentOf(lockDuration: org.joda.time.Duration): FiniteDuration = {
    val ninetyPercentOfMillis: Long = BigDecimal(lockDuration.getMillis * 0.9, new MathContext(2)).toLong
    ninetyPercentOfMillis milliseconds
  }

  private def refreshLock(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId, lockDuration: org.joda.time.Duration)(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Future[Unit] = {
    lockRepo.tryToAcquireOrRenewLock(csid, lockOwnerId, lockDuration).map{ refreshedOk =>
      if (!refreshedOk) {
        val ex = new IllegalStateException(s"[clientSubscriptionId=$csid] Unable to refresh lock")
        throw ex
      }
    }.recover{
      case NonFatal(e) =>
        refreshLockFailed.set(true)
        val msg = "error refreshing lock in timer: " + e.getMessage
        logger.error(msg) //TODO: extend logging API so that we can log an error on a throwable
    }
  }

  private def blockingReleaseLock(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier): Unit = {
    val f = lockRepo.release(csid, lockOwnerId).map { _ =>
      logger.info(s"[clientSubscriptionId=$csid][lockOwnerId=${lockOwnerId.id}] released lock")
    }.recover {
      case NonFatal(_) =>
        val msg = s"[clientSubscriptionId=$csid][lockOwnerId=${lockOwnerId.id}] error releasing lock"
        logger.error(msg) //TODO: extend logging API so that we can log an error on a throwable
    }
    Await.result(f, awaitApiCallDuration)
  }

  private def blockingFetch(csid: ClientSubscriptionId)(implicit hc: HeaderCarrier): Seq[ClientNotification] = {
    try {
      Await.result(repo.fetch(csid), awaitApiCallDuration)
    }
    catch {
      case NonFatal(e) =>
        logger.error(s"[clientSubscriptionId=$csid] error fetching notifications: ${e.getMessage}")
        Seq.empty[ClientNotification]
    }
  }

  protected def process(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Future[Unit] = {
    Future{
      scala.concurrent.blocking {
        blockingOuterProcessLoop(csid, lockOwnerId)
        blockingReleaseLock(csid, lockOwnerId)
      }
    }
  }

  private def blockingOuterProcessLoop(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Unit = {

    logger.info(s"[clientSubscriptionId=$csid] About to push notifications")

      var continue = true
      var counter = 0
      var currentFetchCount = 0
      var previousFetchCount = 0
      try {
        while (continue) {
          try {
            counter += 1
            if (counter % loopIncrementToLog == 0) {
              logger.info(s"[clientSubscriptionId=$csid] processing notification record number $counter")
            }
            val seq = blockingFetch(csid)
            previousFetchCount = currentFetchCount
            currentFetchCount = seq.size
            if (seq.isEmpty || currentFetchCount == previousFetchCount) {
              continue = false // the only way to exit loop is if blockingFetch returns empty list or there is a FATAL exception
            }
            else {
              blockingInnerPushLoop(seq)
              logger.info(s"[clientSubscriptionId=$csid] Push successful")
            }
          } catch {
            case PushProcessingException(_) =>
              blockingEnqueueNotificationsOnPullQueue(csid, lockOwnerId)
            case NonFatal(e) =>
              logger.error(s"[clientSubscriptionId=$csid] error processing notifications: ${e.getMessage}")
          }
        }
      } catch {
        case ExitOuterLoopException(msg) =>
          logger.error(s"Fatal error - exiting processing: $msg")
      }

  }

  protected def blockingInnerPushLoop(clientNotifications: Seq[ClientNotification])(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Unit = {
    clientNotifications.foreach { cn =>
      if (refreshLockFailed.get) {
        throw ExitOuterLoopException(s"[clientSubscriptionId=${cn.csid}] error refreshing lock during push processing")
      }

      val maybeDeclarantCallbackData = blockingMaybeDeclarantDetails(cn)

      maybeDeclarantCallbackData.fold(throw PushProcessingException(s"[clientSubscriptionId=${cn.csid}] Declarant details not found")){ declarantCallbackData =>
        if (declarantCallbackData.callbackUrl.isEmpty) {
          throw PushProcessingException(s"[clientSubscriptionId=${cn.csid}] callbackUrl is empty")
        } else {
          if (push.send(declarantCallbackData, cn)) {
            blockingDeleteNotification(cn)
          } else {
            throw PushProcessingException(s"[clientSubscriptionId=${cn.csid}] Push of notification failed")
          }
        }
      }
    }
  }

  private def blockingMaybeDeclarantDetails(cn: ClientNotification)(implicit hc: HeaderCarrier) = {
    try {
      Await.result(callbackDetailsConnector.getClientData(cn.csid.id.toString), awaitApiCallDuration)
    } catch {
      case NonFatal(e) =>
        throw ExitOuterLoopException(s"Error getting declarant details: ${e.getMessage}")
    }
  }

  private def blockingDeleteNotification(cn: ClientNotification)(implicit hc: HeaderCarrier): Unit = {
    Await.result(
      repo.delete(cn).recover{
        case NonFatal(e) =>
          // we can't do anything other than log delete error
          logger.error(s"${logMsgPrefix(cn)} error deleting notification")
      },
      awaitApiCallDuration)
  }


  private def blockingEnqueueNotificationsOnPullQueue(csid: ClientSubscriptionId, lockOwnerId: LockOwnerId)(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Unit = {
    logger.info(s"[clientSubscriptionId=$csid] About to enqueue notifications to pull queue")

    try {
      blockingInnerPullLoop(blockingFetch(csid))
      logger.info(s"[clientSubscriptionId=$csid] enqueue to pull queue successful")
    } catch {
      case NonFatal(e) =>
        val msg = s"[clientSubscriptionId=$csid] error enqueuing notifications to pull queue: ${e.getMessage}"
        logger.error(msg)
    }

  }

  protected def blockingInnerPullLoop(clientNotifications: Seq[ClientNotification])(implicit hc: HeaderCarrier, refreshLockFailed: AtomicBoolean): Unit = {
    clientNotifications.foreach { cn =>
      if (refreshLockFailed.get) {
        throw ExitOuterLoopException(s"[clientSubscriptionId=${cn.csid}] error refreshing lock during pull processing")
      }
      if (pull.send(cn)) {
        blockingDeleteNotification(cn)
      } else {
        //when both customs-notification-gateway and api-notification-queue are down this exception will guarantee that notifications
        //are sent in order to the notification queue when it comes back up
        throw PullProcessingException("pull queue unavailable")
      }
    }
  }
}
