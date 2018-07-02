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

import akka.actor.ActorSystem
import org.mockito.ArgumentMatchers.{any, eq => ameq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, NotificationQueueConnector, PublicNotificationServiceConnector}
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationConfig, PublicNotificationRequest, PushNotificationConfig}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.{ClientNotificationRepo, LockOwnerId, LockRepo}
import uk.gov.hmrc.customs.notification.services.ClientWorkerImpl
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import unit.services.ClientWorkerTestData._
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData._

import scala.concurrent.Future

class ClientWorkerTimerSpec extends UnitSpec with MockitoSugar with Eventually with BeforeAndAfterAll {

  private val actorSystem = ActorSystem("TestActorSystem")
  private val oneAndAHalfSecondsProcessingDelay = 1500
  private val fiveSecondsProcessingDelay = 5000
  private val lockDurationInMilliseconds = 1000
  private val lockRefreshDurationInMilliseconds = 800

  trait SetUp {

    val mockClientNotificationRepo = mock[ClientNotificationRepo]
    val mockApiSubscriptionFieldsConnector = mock[ApiSubscriptionFieldsConnector]
    val mockPushConnector = mock[PublicNotificationServiceConnector]
    val mockPullConnector = mock[NotificationQueueConnector]
    val mockLockRepo = mock[LockRepo]
    val mockLogger = mock[NotificationLogger]
    val mockCustomsNotificationConfig = mock[CustomsNotificationConfig]
    val mockPushNotificationConfig = mock[PushNotificationConfig]

    def clientWorkerWithProcessingDelay(milliseconds: Int): ClientWorkerImpl = {
      lazy val clientWorker = new ClientWorkerImpl (
        mockCustomsNotificationConfig,
        actorSystem,
        mockClientNotificationRepo,
        mockApiSubscriptionFieldsConnector,
        mockPushConnector,
        mockPullConnector,
        mockLockRepo,
        mockLogger
      ) {
        override def simulatedDelayInMilliSeconds = milliseconds
      }
      clientWorker
    }

    implicit val implicitHc = HeaderCarrier()

    def eqLockOwnerId(id: LockOwnerId): LockOwnerId = ameq[String](id.id).asInstanceOf[LockOwnerId]

    def verifyLogError(msg: String): Unit = {
      PassByNameVerifier(mockLogger, "error")
        .withByNameParam(msg)
        .withParamMatcher(any[HeaderCarrier])
        .verify()
    }

    when(mockCustomsNotificationConfig.pushNotificationConfig).thenReturn(mockPushNotificationConfig)
    when(mockPushNotificationConfig.lockRefreshDurationInMilliseconds).thenReturn(lockRefreshDurationInMilliseconds)
    when(mockPushNotificationConfig.lockDurationInMilliseconds).thenReturn(lockDurationInMilliseconds)
  }

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }

  "ClientWorker" can {
    "In happy path" should {
      "refresh time when elapsed time > time delay duration" in new SetUp {

        when(mockLockRepo.refreshLock(ameq(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])).thenReturn(Future.successful(true))
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPushConnector.send(any[PublicNotificationRequest])).thenReturn(Future.successful(())) // TODO: compare request
        when(mockClientNotificationRepo.delete(ameq("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorkerWithProcessingDelay(fiveSecondsProcessingDelay).processNotificationsFor(CsidOne, CsidOneLockOwnerId))

        actual shouldBe (())
        eventually{
          verify(mockPushConnector).send(any[PublicNotificationRequest]) // TODO: check for equality on request
          verify(mockClientNotificationRepo).delete("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL") // TODO: check for equality on request
          val expectedLockRefreshCount = fiveSecondsProcessingDelay / lockRefreshDurationInMilliseconds
          verify(mockLockRepo, times(expectedLockRefreshCount)).refreshLock(ameq(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])
        }
      }
    }

    "In unhappy path" should {
      "log error when refreshLock returns false, but carry on processing notifications" in new SetUp {

        when(mockLockRepo.refreshLock(ameq(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])).thenReturn(Future.successful(false))
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPushConnector.send(any[PublicNotificationRequest])).thenReturn(Future.successful(())) // TODO: compare request
        when(mockClientNotificationRepo.delete(ameq("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorkerWithProcessingDelay(oneAndAHalfSecondsProcessingDelay).processNotificationsFor(CsidOne, CsidOneLockOwnerId))

        actual shouldBe (())
        eventually{
          verifyLogError("Unable to refresh lock")
          verify(mockPushConnector).send(any[PublicNotificationRequest])
          verify(mockClientNotificationRepo).delete("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")
          verify(mockLockRepo).refreshLock(ameq(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])
        }
      }

      "log error when refreshLock throws an exception, but carry on processing notifications" in new SetUp {

        when(mockLockRepo.refreshLock(ameq(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])).thenReturn(Future.failed(emulatedServiceFailure))
        when(mockClientNotificationRepo.fetch(CsidOne))
          .thenReturn(Future.successful(List(ClientNotificationOne)))
        when(mockApiSubscriptionFieldsConnector.getClientData(ameq(CsidOne.id.toString))(any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(DeclarantCallbackDataOne)))
        when(mockPushConnector.send(any[PublicNotificationRequest])).thenReturn(Future.successful(())) // TODO: compare request
        when(mockClientNotificationRepo.delete(ameq("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")))
          .thenReturn(Future.successful(()))

        val actual = await(clientWorkerWithProcessingDelay(oneAndAHalfSecondsProcessingDelay).processNotificationsFor(CsidOne, CsidOneLockOwnerId))

        actual shouldBe (())
        eventually{
          verifyLogError(emulatedServiceFailure.getMessage)
          verify(mockPushConnector).send(any[PublicNotificationRequest])
          verify(mockClientNotificationRepo).delete("TODO_ADD_MONGO_OBJECT_ID_TO_MODEL")
          verify(mockLockRepo).refreshLock(ameq(CsidOne), eqLockOwnerId(CsidOneLockOwnerId), any[org.joda.time.Duration])
        }
      }
    }

  }

}
