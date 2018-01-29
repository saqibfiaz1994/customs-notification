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

import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, Matchers}
import play.api.libs.json.{JsObject, JsString}
import play.api.mvc._
import play.api.test.Helpers._
import play.mvc.Http.Status.{BAD_REQUEST, NOT_ACCEPTABLE, UNAUTHORIZED, UNSUPPORTED_MEDIA_TYPE}
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse.{UnauthorizedCode, errorBadRequest}
import uk.gov.hmrc.customs.notification.controllers.CustomsNotificationController
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.customs.notification.services.{CustomsNotificationService, DeclarantCallbackDataNotFound, NotificationSent}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.TestData._

import scala.concurrent.Future

class CustomsNotificationControllerSpec extends UnitSpec with Matchers with MockitoSugar with BeforeAndAfterEach {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockCustomsNotificationService = mock[CustomsNotificationService]
  private val mockConfigService = mock[ConfigService]

  private def controller() = new CustomsNotificationController(
    mockNotificationLogger,
    mockCustomsNotificationService,
    mockConfigService
  )

  private val wrongPayloadErrorResult = ErrorResponse.errorBadRequest("Request body does not contain well-formed XML.").XmlResult

  private val clientIdMissingResult = ErrorResponse.errorBadRequest("The X-CDS-Client-ID header is missing").XmlResult

  private val conversationIdMissingResult = ErrorResponse.errorBadRequest("The X-Conversation-ID header is missing").XmlResult

  private val unauthorizedResult = ErrorResponse(UNAUTHORIZED, UnauthorizedCode, "Basic token is missing or not authorized").XmlResult

  private val emulatedServiceFailure = new EmulatedServiceFailure()

  override protected def beforeEach() {
    reset(mockNotificationLogger, mockCustomsNotificationService, mockConfigService)
    when(mockConfigService.maybeBasicAuthToken).thenReturn(Some(basicAuthTokenValue))
  }

  "CustomsNotificationController" should {

    "respond with status 204 for valid request" in {
      when(mockCustomsNotificationService.sendNotification(meq(ValidXML), any[Headers])(any[HeaderCarrier])).thenReturn(NotificationSent)

      testSubmitResult(ValidRequest) { result =>
        status(result) shouldBe NO_CONTENT
      }
    }

    "respond with status 204 for missing Authorization when auth token is not configured" in {
      when(mockCustomsNotificationService.sendNotification(meq(ValidXML), any[Headers])(any[HeaderCarrier])).thenReturn(NotificationSent)
      when(mockConfigService.maybeBasicAuthToken).thenReturn(None)

      testSubmitResult(MissingAuthorizationHeaderRequest) { result =>
        status(result) shouldBe NO_CONTENT
      }
    }

    "respond with status 204 for invalid Authorization when auth token is not configured" in {
      when(mockCustomsNotificationService.sendNotification(meq(ValidXML), any[Headers])(any[HeaderCarrier])).thenReturn(NotificationSent)
      when(mockConfigService.maybeBasicAuthToken).thenReturn(None)

      testSubmitResult(InvalidAuthorizationHeaderRequest) { result =>
        status(result) shouldBe NO_CONTENT
      }
    }

    "respond with 400 when declarant callback data not found by ApiSubscriptionFields service" in {
      when(mockCustomsNotificationService.sendNotification(meq(ValidXML), any[Headers])(any[HeaderCarrier])).thenReturn(DeclarantCallbackDataNotFound)

      testSubmitResult(ValidRequest) { result =>
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
      testSubmitResult(MissingAuthorizationHeaderRequest) { result =>
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

    "propagate a failure for a valid request when service fails" in {
      when(mockCustomsNotificationService.sendNotification(meq(ValidXML), any[Headers])(any[HeaderCarrier])).thenReturn(Future.failed(emulatedServiceFailure))

      testSubmitResult(ValidRequest) { result =>
        intercept[EmulatedServiceFailure](await(result)) shouldBe emulatedServiceFailure
      }
    }

  }

  private def testSubmitResult(request: Request[AnyContent])(test: Future[Result] => Unit) {
    val result = controller().submit().apply(request)
    test(result)
  }

}
