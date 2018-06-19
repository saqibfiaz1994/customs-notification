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

import javax.inject.{Inject, Singleton}

import akka.pattern.ask
import akka.util.Timeout
import play.api.Configuration
import uk.gov.hmrc.customs.notification.actors.AkkaBootstrap
import uk.gov.hmrc.customs.notification.actors.RootActor.SendNotificationMsg
import uk.gov.hmrc.customs.notification.connectors.{GoogleAnalyticsSenderConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain.{DeclarantCallbackData, PublicNotificationRequest}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.model.ClientId
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.xml.NodeSeq

@Singleton
class CustomsNotificationServiceForSpike @Inject()(logger: NotificationLogger,
                                                   publicNotificationRequestService: PublicNotificationRequestService,
                                                   pushConnector: PublicNotificationServiceConnector,
                                                   queueConnector: NotificationQueueConnector,
                                                   gaConnector: GoogleAnalyticsSenderConnector,
                                                   configuration: Configuration,
                                                   akkaBootstrap: AkkaBootstrap
                                          ) {

  // bootstrap akka cluster sharding
  val askTimeout = 10 seconds
  val port: Int = configuration.getInt("shard-node-port").get
println(s"XXXXXXXXXXXXXXXXXXXXXXXXXX shard-node-port=$port")

  def handleNotification(clientId: ClientId, xml: NodeSeq, callbackDetails: DeclarantCallbackData, metaData: RequestMetaData)(implicit hc: HeaderCarrier): Future[Any] = {
    gaConnector.send("notificationRequestReceived", s"[ConversationId=${metaData.conversationId}] A notification received for delivery")

    val publicNotificationRequest = publicNotificationRequestService.createRequest(xml, callbackDetails, metaData)

    // SendNotificationMsg to root actor
    implicit val timeout: Timeout = Timeout(askTimeout)
    val f: Future[Any] = akkaBootstrap.rootActor ? SendNotificationMsg(clientId, publicNotificationRequest)
    f.map(msg => logger.info("Application got acknowledgement " + msg))

//    if (callbackDetails.callbackUrl.isEmpty) {
//      logger.info("Notification will be enqueued as callbackUrl is empty")
//      passOnToPullQueue(publicNotificationRequest)
//    } else {
//      pushAndThenPassOnToPullIfPushFails(publicNotificationRequest)
//    }
  }


  private def pushAndThenPassOnToPullIfPushFails(publicNotificationRequest: PublicNotificationRequest)(implicit hc: HeaderCarrier): Future[Unit] = {
    pushConnector.send(publicNotificationRequest)
      .flatMap { _ =>
        logger.info("Notification has been pushed")
        gaConnector.send("notificationPushRequestSuccess", s"[ConversationId=${publicNotificationRequest.body.conversationId}] A notification has been pushed successfully")
      }.recover {
      case _: Throwable =>
        gaConnector.send("notificationPushRequestFailed", s"[ConversationId=${publicNotificationRequest.body.conversationId}] A notification Push request failed")
        passOnToPullQueue(publicNotificationRequest)
    }
  }

  private def passOnToPullQueue(publicNotificationRequest: PublicNotificationRequest)(implicit hc: HeaderCarrier): Future[Unit] = {
    queueConnector.enqueue(publicNotificationRequest)
      .map { _ =>
        gaConnector.send("notificationLeftToBePulled", s"[ConversationId=${publicNotificationRequest.body.conversationId}] A notification has been left to be pulled")
        logger.info("Notification has been passed on to PULL service")(hc)
        ()
      }.recover {
      case t: Throwable =>
        gaConnector.send("notificationPullRequestFailed", s"[ConversationId=${publicNotificationRequest.body.conversationId}] A notification Pull request failed")
    }
  }
}
