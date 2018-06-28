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
import org.mockito.Mockito._
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
import util.TestData.emulatedServiceFailure

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

    def verifyLogError(msg: String): Unit = {
      PassByNameVerifier(mockLogger, "error")
        .withByNameParam(msg)
        .withParamMatcher(any[HeaderCarrier])
        .verify()
    }

    def verifyLogInfo(msg: String): Unit = {
      PassByNameVerifier(mockLogger, "info")
        .withByNameParam(msg)
        .withParamMatcher(any[HeaderCarrier])
        .verify()
    }

    def schedulerExpectations(): Unit = {
      when(mockActorSystem.scheduler).thenReturn(mockScheduler)
      when(mockScheduler.schedule(any[FiniteDuration], any[FiniteDuration], any[Runnable])(any[ExecutionContext])).thenReturn(mockCancelable)
    }

  }

  "ClientWorker" can {
    "In happy path" should {
      "send 2 client notifications when 2 found by fetch from the database" in new SetUp {
        schedulerExpectations()
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne, ClientNotificationTwo)), Future.successful(List()))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPushConnector.send(any[PublicNotificationRequest])).thenReturn(Future.successful(())) // TODO: compare request
        when(mockClientNotificationRepo.delete(ameq("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorker.processNotificationsFor(CsidOne))

        actual shouldBe (())
        eventually{
          verify(mockPushConnector).send(ameq(pnrOne))
          verify(mockPushConnector).send(ameq(pnrTwo))
          verify(mockClientNotificationRepo, times(2)).delete("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL") // TODO: check for equality on request
          verify(mockCancelable).cancel()
          verifyZeroInteractions(mockLockRepo)
          verifyLogInfo("Whoo Hooo!")
        }

      }
    }

    "In un-happy path" should {
      "log error when fetch client notifications fail" in new SetUp {
        schedulerExpectations()
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.failed(emulatedServiceFailure))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPushConnector.send(any[PublicNotificationRequest])).thenReturn(Future.successful(())) // TODO: compare request
        when(mockClientNotificationRepo.delete(ameq("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorker.processNotificationsFor(CsidOne))

        actual shouldBe (())
        eventually{
          verifyLogError(emulatedServiceFailure.getMessage)
          verifyZeroInteractions(mockApiSubscriptionFieldsConnector)
          verifyZeroInteractions(mockPushConnector)
          verify(mockCancelable).cancel()
        }

      }

      "log error when api subscription fields call fails" in new SetUp {
        schedulerExpectations()
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.failed(emulatedServiceFailure))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPushConnector.send(any[PublicNotificationRequest])).thenReturn(Future.successful(())) // TODO: compare request
        when(mockClientNotificationRepo.delete(ameq("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorker.processNotificationsFor(CsidOne))

        actual shouldBe (())
        eventually{
          verifyLogError(emulatedServiceFailure.getMessage)
          verifyZeroInteractions(mockApiSubscriptionFieldsConnector)
          verifyZeroInteractions(mockPushConnector)
        }

      }
    }
  }

}
