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

import java.util.concurrent.TimeUnit
import javax.inject.Singleton

import akka.actor.ActorSystem
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockRepo}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class SendException(cause: Throwable) extends Exception(cause)


/*
TODO
- wire in dummy config service case class that has lockRefresh delay AND simulated processing delay (for unit tests)
 */
@Singleton
class ClientWorkerImpl(
                        actorSystem: ActorSystem,
                        repo: ClientNotificationRepo,
                        callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                        pushConnector: PublicNotificationServiceConnector,
                        pullConnector: NotificationQueueConnector,
                        lockRepo: LockRepo,
                        logger: NotificationLogger
                      ) extends ClientWorker {

  //TODO: read from config
  val extendLockDuration = Duration(1, TimeUnit.SECONDS)

  override def processNotificationsFor(csid: ClientSubscriptionId): Future[Unit] /*(implicit hc: HeaderCarrier) ?????*/ = {
    //implicit HeaderCarrier required for ApiSubscriptionFieldsConnector
    //however looking at api-subscription-fields service I do not think it is required so keep new HeaderCarrier() for now
    implicit val hc = HeaderCarrier()

    val timer = actorSystem.scheduler.schedule(Duration(1, TimeUnit.SECONDS), Duration(1, TimeUnit.SECONDS), new Runnable {
      override def run() = {
        logger.debug("TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! ")

        lockRepo.refreshLock(csid, extendLockDuration).map{ refreshedOk =>
          if (!refreshedOk) {
            //TODO: how to stop all processing at this point?
            val ex = new IllegalArgumentException("Unable to refresh lock")
            logger.error(ex.getMessage) //TODO: extend logging API
            throw ex
          }
        } // TODO: what about failed future?
      }
    })

    // cleanup timer
    process(csid).onComplete { _ =>
      logger.debug(s"XXXXXXXXXXXXXXXXXXX about to cancel timer")
      val cancelled = timer.cancel()
      logger.debug(s"XXXXXXXXXXXXXXXXXXX cancelled = $cancelled")
    }

    //TODO: revisit after timer lifecycle management has been refined
    Future.successful(())
  }

  private def process(csid: ClientSubscriptionId)(implicit hc: HeaderCarrier): Future[Unit] = {

    scala.concurrent.blocking {
      Thread.sleep(5000)
    }

    logger.info("Whoo Hooo!")

    (for {
      clientNotifications <- repo.fetch(csid)
      cnTuples = clientNotifications.map(cn => (csid, cn))
      _ <- sequence(cnTuples)(pushClientNotification)
    } yield ())
    .recover {
      case e: Exception =>
        logger.error(e.getMessage)
        enqueueClientNotificationsToPullQueue(csid)
    }

  }

  private def pushClientNotification(cnTuple: (ClientSubscriptionId, ClientNotification))(implicit hc: HeaderCarrier): Future[Unit] = {
    val csid = cnTuple._1
    val cn = cnTuple._2

    for {
      request <- eventualPublicNotificationRequest(csid, cn)
      _ <- pushConnector.send(request)
      _ <- repo.delete("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")
    } yield ()
  }

  private def eventualPublicNotificationRequest(csid: ClientSubscriptionId, cn: ClientNotification)(implicit hc: HeaderCarrier): Future[PublicNotificationRequest] = {
    val futureMaybeCallbackDetails: Future[Option[DeclarantCallbackData]] = callbackDetailsConnector.getClientData(csid.id.toString)
    futureMaybeCallbackDetails.map{ maybeCallbackDetails =>
      val declarantCallbackData = maybeCallbackDetails.fold(throw new IllegalStateException("TODO: figure out processing")){ cd => cd}

      logger.debug(s"3. XXXXXXXXXXXXXXXXXXXX $maybeCallbackDetails")

      val request = publicNotificationRequest(csid, declarantCallbackData, cn)

      logger.debug(s"4. XXXXXXXXXXXXXXXXXXXX $request")
      request
    }
  }

  private def publicNotificationRequest(
    csid: ClientSubscriptionId,
    declarantCallbackData: DeclarantCallbackData,
    cn: ClientNotification): PublicNotificationRequest = {

    PublicNotificationRequest(
      csid.id.toString,
      PublicNotificationRequestBody(
        declarantCallbackData.callbackUrl,
        declarantCallbackData.securityToken,
        "TODO_ADD_CONVERSATION_ID_TO_MODEL",
        cn.notification.headers.map(t => Header(t._1, t._2)),
        cn.notification.payload
      ))
  }


  private def enqueueClientNotificationsToPullQueue(csid: ClientSubscriptionId)(implicit hc: HeaderCarrier): Future[Unit] = {
    repo.fetch(csid).map{clientNotifications =>
      logger.debug(s"1. XXXXXXXXXXXXXXXXXXXX $clientNotifications")
      val cnTuples = clientNotifications.map(cn => (csid, cn))
      sequence(cnTuples)(enqueueClientNotificationToPullQueue).recover{
        case e: Exception =>
          logger.error(e.getMessage) // TODO: extend logging API
      }
    } // fetch

    Future.successful(())
  }

  private def enqueueClientNotificationToPullQueue(cnTuple: (ClientSubscriptionId, ClientNotification))(implicit hc: HeaderCarrier): Future[Unit] = {
    val csid = cnTuple._1
    val cn = cnTuple._2

    for {
      request <- eventualPublicNotificationRequest(csid, cn)
      _ <- pullConnector.enqueue(request)
    } yield ()
  }


  private def sequence[A, B](iter: Iterable[A])(fn: A => Future[B])
                            (implicit ec: ExecutionContext): Future[List[B]] =
    iter.foldLeft(Future(List.empty[B])) {
      (previousFuture, next) =>
        for {
          previousResults <- previousFuture
          next <- fn(next)
        } yield previousResults :+ next
    }


}
