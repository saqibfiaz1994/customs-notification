/*
 * Copyright 2019 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.{refEq, eq => meq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Millis, Span}
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.controllers.RequestMetaData
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepo
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationClientWorkerService, NotificationDispatcher, PullClientNotificationService}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationClientWorkerServiceSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  override implicit def patienceConfig: PatienceConfig =
    super.patienceConfig.copy(timeout = Span(defaultTimeout.toMillis, Millis))

  private val badgeIdValue = "test-badge-id"
  private val hc: HeaderCarrier = HeaderCarrier(extraHeaders = Seq(
    X_CONVERSATION_ID_HEADER_NAME -> validConversationId,
    X_BADGE_ID_HEADER_NAME -> badgeIdValue,
    X_EORI_ID_HEADER_NAME -> eoriNumber,
    X_CDS_CLIENT_ID_HEADER_NAME -> validFieldsId,
    X_CORRELATION_ID_HEADER_NAME -> correlationId))

  private val mockNotificationLogger = mock[NotificationLogger]
  private val requestMetaData = RequestMetaData(clientSubscriptionId, conversationId, Some(BadgeId(badgeIdValue)), Some(Eori(eoriNumber)), Some(CorrelationId(correlationId)), TimeReceivedZoned)
  private val mockClientNotificationRepo = mock[ClientNotificationRepo]
  private val mockNotificationDispatcher = mock[NotificationDispatcher]
  private val contentType = "application/xml"
  private val badgeIdHeader: (String, String) = hc.headers.filter(a => a._1 == X_BADGE_ID_HEADER_NAME).head
  private val eoriIdHeader: (String, String) = hc.headers.filter(a => a._1 == X_EORI_ID_HEADER_NAME).head
  private val correlationIdHeader: (String, String) = hc.headers.filter(a => a._1 == X_CORRELATION_ID_HEADER_NAME).head
  private val expectedHeaders = Seq(Header(badgeIdHeader._1, badgeIdHeader._2), Header(eoriIdHeader._1, eoriIdHeader._2), Header(correlationIdHeader._1, correlationIdHeader._2))
  private val notification = Notification(conversationId, expectedHeaders, pushNotificationRequest.body.xmlPayload, contentType)
  private val clientNotification = ClientNotification(clientSubscriptionId, notification, None, Some(TimeReceivedDateTime))
  private val mockPullService = mock[PullClientNotificationService]

   private val service = new CustomsNotificationClientWorkerService(
    mockNotificationLogger,
    mockClientNotificationRepo,
    mockNotificationDispatcher,
    mockPullService
  )

  override protected def beforeEach() {
    reset(mockNotificationDispatcher, mockClientNotificationRepo)
    when(mockClientNotificationRepo.save(refEq(clientNotification, "timeReceived", "id"))).thenReturn(Future.successful(true))
    when(mockNotificationDispatcher.process(meq(Set(clientSubscriptionId)))).thenReturn(Future.successful(()))
  }

  "CustomsNotificationClientWorkerService" should {

    "first try to Push the notification" in {
      val result = service.handleNotification(ValidXML, requestMetaData)
      await(result) shouldBe true
      eventually(verify(mockClientNotificationRepo).save(refEq(clientNotification, "timeReceived", "id")))
      eventually(verify(mockNotificationDispatcher).process(meq(Set(clientSubscriptionId))))
      verifyZeroInteractions(mockPullService)
    }

    "fails when was unable to save notification to repository" in {
      when(mockClientNotificationRepo.save(refEq(clientNotification, "timeReceived", "id"))).thenReturn(Future.successful(false))

      val result = service.handleNotification(ValidXML, requestMetaData)

      await(result) shouldBe false
      verifyZeroInteractions(mockNotificationDispatcher)
      verifyZeroInteractions(mockPullService)
    }

    "fails when was unable to save notification to repository due to unexpected exception" in {
      when(mockClientNotificationRepo.save(refEq(clientNotification, "timeReceived", "id"))).thenReturn(Future.failed(new RuntimeException("save gone wrong")))

      val result = service.handleNotification(ValidXML, requestMetaData)

      await(result) shouldBe false
      verifyZeroInteractions(mockNotificationDispatcher)
      verifyZeroInteractions(mockPullService)
    }
  }
}
