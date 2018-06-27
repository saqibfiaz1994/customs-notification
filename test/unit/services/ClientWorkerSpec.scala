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

package unit.services

import java.util.UUID

import akka.actor.{ActorSystem, Cancellable, Scheduler}
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.{any, eq => ameq}
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockRepo}
import uk.gov.hmrc.customs.notification.services.ClientWorkerImpl
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData.{ClientNotification1, DeclarantCallbackDataOne, TestClientSubscriptionId}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

object ClientWorkerTestData {
  val ClientSubscriptionIdAsString = "eaca01f9-ec3b-4ede-b263-61b626dde232"
  val TestClientSubscriptionId = ClientSubscriptionId(UUID.fromString(ClientSubscriptionIdAsString))
  val Headers = Seq("h1" -> "v1")
  val Payload = "PAYLOAD"
  val ContentType = "CONTENT_TYPE"
  val notification1 = Notification(Headers, Payload, ContentType)
  val TimeStampOne = DateTime.now
  val ClientNotification1 = ClientNotification(TestClientSubscriptionId, notification1, TimeStampOne)
  val DeclarantCallbackDataOne = DeclarantCallbackData("URL", "SECURITY_TOKEN")
}

class ClientWorkerSpec extends UnitSpec with MockitoSugar with Eventually {

  trait SetUp {

    val mockActorSystem = mock[ActorSystem]
    val mockScheduler = mock[Scheduler]
    val mockCancelable = mock[Cancellable]
    val actorSystem = ActorSystem("TestActorSystem")

    val mockClientNotificationRepo = mock[ClientNotificationRepo]
    val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
    val mockPushConnector = mock[PublicNotificationServiceConnector]
    val mockPullConnector = mock[NotificationQueueConnector]
    val mockLockRepo = mock[LockRepo]
    val mockNotificationLogger = mock[NotificationLogger]
    val clientWorker = new ClientWorkerImpl(
      mockActorSystem, // actorSystem
      mockClientNotificationRepo,
      mockApiSubscriptionFieldsConnector,
      mockPushConnector,
      mockPullConnector,
      mockLockRepo,
      mockNotificationLogger
    )

    implicit val implicitHc = HeaderCarrier()
  }

  "ClientWorker" can {
    "In happy path" should {
      "send notifications when elapsed processing time < lock timeout duration" in new SetUp {
        when(mockActorSystem.scheduler).thenReturn(mockScheduler)
        when(mockScheduler.schedule(any[FiniteDuration], any[FiniteDuration], any[Runnable])(any[ExecutionContext])).thenReturn(mockCancelable)
        when(mockClientNotificationRepo.fetch(TestClientSubscriptionId))
          .thenReturn(Future.successful(List(ClientNotification1)))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(TestClientSubscriptionId.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPushConnector.send(any[PublicNotificationRequest])).thenReturn(Future.successful(())) // TODO: compare request
        when(mockClientNotificationRepo.delete(ameq("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorker.processNotificationsFor(TestClientSubscriptionId))

        actual shouldBe ()
        eventually{
          verify(mockPushConnector).send(any[PublicNotificationRequest]) // TODO: check for equality on request
          verify(mockClientNotificationRepo).delete("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL") // TODO: check for equality on request
          verify(mockCancelable).cancel()
          verifyZeroInteractions(mockLockRepo)
        }

//        Thread.sleep(10000)
      }
    }
    "In un-happy path" should {

    }
  }
}

