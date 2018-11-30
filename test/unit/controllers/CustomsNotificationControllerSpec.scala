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

package unit.controllers

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, Matchers}
import org.scalatest.concurrent.Eventually
import play.api.libs.json.{JsObject, JsString}
import play.api.mvc._
import play.api.test.Helpers._
import play.mvc.Http.Status.{BAD_REQUEST, NOT_ACCEPTABLE, UNAUTHORIZED, UNSUPPORTED_MEDIA_TYPE}
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{UnauthorizedCode, errorBadRequest}
import uk.gov.hmrc.customs.notification.connectors.{ApiSubscriptionFieldsConnector, CustomsNotificationMetricsConnector}
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames.{X_BADGE_ID_HEADER_NAME, X_CORRELATION_ID_HEADER_NAME, X_EORI_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.controllers.{CustomsNotificationController, RequestMetaData}
import uk.gov.hmrc.customs.notification.domain.{CustomsNotificationsMetricsRequest, DeclarantCallbackData, Header}
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, DateTimeService}
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationControllerSpec extends UnitSpec with Matchers with MockitoSugar with BeforeAndAfterEach {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockCustomsNotificationService = mock[CustomsNotificationService]
  private val mockConfigService = mock[ConfigService]
  private val mockCallbackDetailsConnector = mock[ApiSubscriptionFieldsConnector]
  private val mockCallbackDetails = mock[DeclarantCallbackData]
  private val mockDateTimeService = mock[DateTimeService]
  private val mockCustomsNotificationsMetricsConnector = mock[CustomsNotificationMetricsConnector]


  private def controller() = new CustomsNotificationController(
    mockNotificationLogger,
    mockCustomsNotificationService,
    mockCallbackDetailsConnector,
    mockConfigService,
    mockDateTimeService,
    mockCustomsNotificationsMetricsConnector
  )

  private val wrongPayloadErrorResult = ErrorResponse.errorBadRequest("Request body does not contain well-formed XML.").XmlResult

  private val internalServerError = ErrorResponse.errorInternalServerError("Internal Server Error").XmlResult

  private def internalServerError(msg: String) = ErrorResponse.errorInternalServerError(msg).XmlResult

  private val clientIdMissingResult = ErrorResponse.errorBadRequest("The X-CDS-Client-ID header is missing").XmlResult

  private val conversationIdMissingResult = ErrorResponse.errorBadRequest("The X-Conversation-ID header is missing").XmlResult

  private val unauthorizedResult = ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "Basic token is missing or not authorized").XmlResult

  private val expectedRequestMetaData = RequestMetaData(clientSubscriptionId, conversationId, Some(Header(X_BADGE_ID_HEADER_NAME, badgeId)), Some(Header(X_EORI_ID_HEADER_NAME, eoriNumber)), Some(Header(X_CORRELATION_ID_HEADER_NAME, correlationId)), mockDateTimeService.zonedDateTimeUtc)

  private val eventualTrue = Future.successful(true)

  private val eventualFalse = Future.successful(false)

  override protected def beforeEach() {
    reset(mockNotificationLogger, mockCustomsNotificationService, mockCallbackDetailsConnector, mockConfigService, mockDateTimeService)
    when(mockConfigService.maybeBasicAuthToken).thenReturn(Some(basicAuthTokenValue))
    when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData))(any[HeaderCarrier])).thenReturn(eventualTrue)
  }

  "CustomsNotificationController" should {

    "respond with status 202 for valid request" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockCustomsNotificationsMetricsConnector.post(any[CustomsNotificationsMetricsRequest])).thenReturn(Future.successful(()))
      testSubmitResult(ValidRequestWithMixedCaseCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }
        verifyMetricsConnector
        verify(mockCustomsNotificationService).handleNotification(meq(ValidXML),  meq(expectedRequestMetaData))(any[HeaderCarrier])
    }

    "respond with status 202 for missing Authorization when auth token is not configured" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockConfigService.maybeBasicAuthToken).thenReturn(None)
      when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(mayBeBadgeId = None, mayBeEoriNumber = None)))(any[HeaderCarrier])).thenReturn(eventualTrue)

      testSubmitResult(MissingAuthorizationHeaderRequestWithCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }

      verify(mockCustomsNotificationService).handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(mayBeBadgeId = None, mayBeEoriNumber = None)))(any[HeaderCarrier])
    }

    "respond with status 202 for invalid Authorization when auth token is not configured" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockConfigService.maybeBasicAuthToken).thenReturn(None)
      when(mockCustomsNotificationService.handleNotification(meq(ValidXML), meq(expectedRequestMetaData.copy(mayBeBadgeId = None, mayBeEoriNumber = None)))(any[HeaderCarrier])).thenReturn(eventualTrue)

      testSubmitResult(InvalidAuthorizationHeaderRequestWithCorrelationId) { result =>
        status(result) shouldBe ACCEPTED
      }
    }

    "respond with 400 when declarant callback data not found by ApiSubscriptionFields service" in {
      when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier])).thenReturn(Future.successful(None))

      testSubmitResult(ValidRequestWithMixedCaseCorrelationId) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe errorBadRequest("The X-CDS-Client-ID header value is invalid").XmlResult
      }
    }

    "respond with status 415 for an invalid content type request header" in {
      testSubmitResult(InvalidContentTypeHeaderRequest) { result =>
        status(result) shouldBe UNSUPPORTED_MEDIA_TYPE
        await(result) shouldBe ErrorResponse.ErrorContentTypeHeaderInvalid.XmlResult
      }
    }

    "respond with status 406 for an invalid accept request header" in {
      testSubmitResult(InvalidAcceptHeaderRequest) { result =>
        status(result) shouldBe NOT_ACCEPTABLE
        await(result) shouldBe ErrorResponse.ErrorAcceptHeaderInvalid.XmlResult
      }
    }

    "respond with status 400 for missing X-Conversation-ID" in {
      testSubmitResult(MissingConversationIdHeaderRequest) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe conversationIdMissingResult
      }
    }

    "respond with status 400 for missing X-CDS-Client-ID" in {
      testSubmitResult(MissingClientIdHeaderRequest) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe clientIdMissingResult
      }
    }

    "respond with status 401 for missing Authorization" in {
      testSubmitResult(MissingAuthorizationHeaderRequestWithCorrelationId) { result =>
        status(result) shouldBe UNAUTHORIZED
        await(result) shouldBe unauthorizedResult
      }
    }

    "respond with status 401 for invalid Authorization" in {
      testSubmitResult(InvalidAuthorizationHeaderRequest) { result =>
        status(result) shouldBe UNAUTHORIZED
        await(result) shouldBe unauthorizedResult
      }
    }

    "respond with status 400 for a non well-formed XML payload" in {
      testSubmitResult(ValidRequest.withTextBody("<xml><non_well_formed></xml>")) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe wrongPayloadErrorResult
      }
    }

    "respond with status 400 for a non XML payload" in {
      testSubmitResult(ValidRequest.withJsonBody(JsObject(Seq("something" -> JsString("I am a json"))))) { result =>
        status(result) shouldBe BAD_REQUEST
        await(result) shouldBe wrongPayloadErrorResult
      }
    }

    "respond with 500 when unexpected failure happens" in {
      when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier]))
        .thenReturn(Future.failed(emulatedServiceFailure))


      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
        await(result) shouldBe internalServerError
      }
    }

    "respond with status 500 when handle notification fails" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockCustomsNotificationService.handleNotification(any(), any())(any()))
        .thenReturn(eventualFalse)

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }
    }

    "respond with status 500 when handle notification fails unexpectedly" in {
      returnMockedCallbackDetailsForTheClientIdInRequest()
      when(mockCustomsNotificationService.handleNotification(any(), any())(any()))
        .thenThrow(emulatedServiceFailure)

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe INTERNAL_SERVER_ERROR
      }
    }

  }

  private def returnMockedCallbackDetailsForTheClientIdInRequest() = {
    when(mockCallbackDetailsConnector.getClientData(meq(validFieldsId))(any[HeaderCarrier])).
      thenReturn(Future.successful(Some(mockCallbackDetails)))
  }

  private def testSubmitResult(request: Request[AnyContent])(test: Future[Result] => Unit) {
    val result = controller().submit().apply(request)
    test(result)
  }

  def verifyMetricsConnector(): Unit = {
    val metricsRequestCaptor: ArgumentCaptor[CustomsNotificationsMetricsRequest] = ArgumentCaptor.forClass(classOf[CustomsNotificationsMetricsRequest])
    Eventually.eventually(verify(mockCustomsNotificationsMetricsConnector, times(1)).post(metricsRequestCaptor.capture()))
    val metricsRequest = metricsRequestCaptor.getValue
    metricsRequest.conversationId shouldBe expectedRequestMetaData.conversationId
    ()
  }
}
