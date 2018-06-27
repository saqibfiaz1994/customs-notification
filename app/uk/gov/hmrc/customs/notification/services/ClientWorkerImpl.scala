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

import akka.actor.{ActorSystem, Cancellable}
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockRepo}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

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

  val extendLockDuration = Duration(1, TimeUnit.SECONDS)

  override def processNotificationsFor(csid: ClientSubscriptionId)/*(implicit hc: HeaderCarrier) ?????*/: Future[Unit] = {
    val timer: Cancellable = actorSystem.scheduler.schedule(Duration(1, TimeUnit.SECONDS), Duration(1, TimeUnit.SECONDS), new Runnable {
      override def run() = {
        debug("TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! TIMER! ")
        // TODO: wht about failed future?
        lockRepo.refreshLock(csid, extendLockDuration).map{refreshedOk => error(new IllegalStateException("Unable to refresh lock")) }
      }
    })

    process(csid).onComplete(_ => timer.cancel())

    Future.successful(())
  } // processNotificationsFor

  private def pnr(csid: ClientSubscriptionId, declarantCallbackData: DeclarantCallbackData, cn: ClientNotification) = {
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

  private def process(csid: ClientSubscriptionId): Future[Unit] = {
    //required for ApiSubscriptionFieldsConnector
    //however looking at ApiSubscriptionFields I think it not required so keep new HeaderCarrier()
    implicit val hc = HeaderCarrier()
    repo.fetch(csid).map{clientNotifications =>
      debug(s"1. XXXXXXXXXXXXXXXXXXXX $clientNotifications")
      clientNotifications.foreach{ cn =>
        debug(s"2. XXXXXXXXXXXXXXXXXXXX $cn")
        val futureMaybeCallbackDetails: Future[Option[DeclarantCallbackData]] = callbackDetailsConnector.getClientData(csid.id.toString)
        futureMaybeCallbackDetails.map{ maybeCallbackDetails =>
          val declarantCallbackData = maybeCallbackDetails.fold(throw new IllegalStateException("TODO: figure out processing")){ cd => cd}

          debug(s"3. XXXXXXXXXXXXXXXXXXXX $maybeCallbackDetails")

          val request = pnr(csid, declarantCallbackData, cn)

          debug(s"4. XXXXXXXXXXXXXXXXXXXX $request")

          // TODO: pass in a function that takes a PNR request and return a future of unit

          pushConnector.send(request).map{_ =>
            debug(s"5. XXXXXXXXXXXXXXXXXXXX about to delete")
            repo.delete("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")
          }.recover{
            case e: Exception =>
              error(e)
              //TODO:
              sendClientNotificationsToPullQueue(csid)
          }
        }

      } // forEach
    } // fetch
  }


  private def sendClientNotificationsToPullQueue(csid: ClientSubscriptionId): Future[Unit] = {
    Future.successful(()) //TODO
  }

  private def debug(s: String) = println(s)
  private def error(e: Throwable) = println(e.getStackTrace)
}
