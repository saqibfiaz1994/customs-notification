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

import akka.actor.{ActorSystem, Cancellable, Scheduler}
import org.mockito.ArgumentMatchers.{any, eq => ameq}
import org.mockito.Mockito.{verify, verifyZeroInteractions, when}
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain.PublicNotificationRequest
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockRepo}
import uk.gov.hmrc.customs.notification.services.ClientWorkerImpl
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData._
import util.MockitoPassByNameHelper.PassByNameVerifier

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class ClientWorkerSpec extends UnitSpec with MockitoSugar with Eventually {

  trait SetUp {

    val mockActorSystem = mock[ActorSystem]
    val mockScheduler = mock[Scheduler]
    val mockCancelable = mock[Cancellable]

    val mockClientNotificationRepo = mock[ClientNotificationRepo]
    val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
    val mockPushConnector = mock[PublicNotificationServiceConnector]
    val mockPullConnector = mock[NotificationQueueConnector]
    val mockLockRepo = mock[LockRepo]
    val mockLogger = mock[NotificationLogger]
    val clientWorker = new ClientWorkerImpl(
      mockActorSystem,
      mockClientNotificationRepo,
      mockApiSubscriptionFieldsConnector,
      mockPushConnector,
      mockPullConnector,
      mockLockRepo,
      mockLogger
    )

    implicit val implicitHc = HeaderCarrier()
  }

  "ClientWorker" can {
    "In happy path" should {
      "send notifications when elapsed processing time < lock timeout duration" in new SetUp {
        when(mockActorSystem.scheduler).thenReturn(mockScheduler)
        when(mockScheduler.schedule(any[FiniteDuration], any[FiniteDuration], any[Runnable])(any[ExecutionContext])).thenReturn(mockCancelable)
        when(mockClientNotificationRepo.fetch(TestClientSubscriptionId))
          .thenReturn(Future.successful(List(ClientNotification1)), Future.successful(List()))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(TestClientSubscriptionId.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPushConnector.send(any[PublicNotificationRequest])).thenReturn(Future.successful(())) // TODO: compare request
        when(mockClientNotificationRepo.delete(ameq("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorker.processNotificationsFor(TestClientSubscriptionId))

        actual shouldBe (())

        eventually{
          verify(mockPushConnector).send(any[PublicNotificationRequest]) // TODO: check for equality on request
          verify(mockClientNotificationRepo).delete("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL") // TODO: check for equality on request
          verify(mockCancelable).cancel()
          verifyZeroInteractions(mockLockRepo)
          PassByNameVerifier(mockLogger, "info")
            .withByNameParam("Whoo Hooo!")
            .withParamMatcher(any[HeaderCarrier])
            .verify()
        }

      }
    }

//    "In un-happy path" should {
//      "log error when fetch client notifications fail" in new SetUp {
//
//      }
//    }
  }
}
