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

package acceptance

import org.joda.time.DateTime
import org.scalatest.{Matchers, OptionValues}
import play.api.http.HeaderNames.{ACCEPT => _, CONTENT_TYPE => _}
import play.api.http.MimeTypes
import play.api.mvc.AnyContentAsXml
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.ClientNotificationMongoRepo
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import util.TestData.TimeReceivedDateTime
import util._

import scala.concurrent.ExecutionContext.Implicits.global

class PushPullNotificationsSpec extends AcceptanceTestSpec
  with Matchers with OptionValues
  with ApiSubscriptionFieldsService
  with StubForPushService
  with StubForPullService
  with MongoSpecSupport
  with NotificationQueueService
  with PushNotificationService
  with PushPullNotificationsVerifier
  with PushPullDBInsertionTestDataFeeder {

  lazy val pushServiceStub = this
  lazy val pullQStub = this
  lazy val makeAPICall = makeLocalCall
  lazy val apiSubscriptionFieldsService = this

  private val endpoint = "/customs-notification/notify"
  private val totalNotificationsToBeSent = 100
  private val numberOfClientsToTest = 10

  private val notificationRepo = app.injector.instanceOf[ClientNotificationMongoRepo]

  private val httpClient = app.injector.instanceOf[HttpClient]

  override protected def beforeAll() {
    startMockServer()
  }

  override protected def beforeEach(): Unit = {
    resetMockServer()
    await(notificationRepo.drop(scala.concurrent.ExecutionContext.Implicits.global))
    runNotificationQueueService()
    setupPushNotificationServiceToReturn()
  }

  override protected def afterAll() {
    stopMockServer()
  }

  private def fakeRequestToAPI(expectedCall: ExpectedCall): FakeRequest[AnyContentAsXml] = {
    val fixedHeaders: List[(String, String)] = List[(String, String)](
      X_CDS_CLIENT_ID_HEADER_NAME -> expectedCall.client.csid.toString,
      X_CONVERSATION_ID_HEADER_NAME -> expectedCall.conversationId.toString,
      RequestHeaders.CONTENT_TYPE_HEADER,
      RequestHeaders.ACCEPT_HEADER,
      RequestHeaders.BASIC_AUTH_HEADER) ::: expectedCall.maybeBadgeId.fold(List[(String, String)]())(x => List[(String, String)]((X_BADGE_ID_HEADER_NAME -> x)))

    lazy val requestToAPI: FakeRequest[AnyContentAsXml] = FakeRequest(method = POST, path = endpoint)
      .withHeaders(fixedHeaders: _*)
      .withXmlBody(expectedCall.xml)

    requestToAPI

  }

  private def callAPIAndMakeSureItReturns202(requestToAPI: FakeRequest[AnyContentAsXml]) = {
    status(route(app, requestToAPI).value) shouldBe ACCEPTED
  }

  private def callRemoteAPIAndMakeSureItReturns202(requestToAPI: FakeRequest[AnyContentAsXml]) = {
    implicit val hc = HeaderCarrier()
    val body = requestToAPI.body.asXml.get.toString()
    val headers = requestToAPI.headers.headers
    val result: HttpResponse = await(httpClient.POSTString("http://192.168.160.60:9821/customs-notification/notify", body, headers))

    result.status shouldBe ACCEPTED
  }

  private val makeLocalCall: ExpectedCall => Unit = expectedCall => callAPIAndMakeSureItReturns202(fakeRequestToAPI(expectedCall))
  private val makeRemoteAPICall: ExpectedCall => Unit = expectedCall => callRemoteAPIAndMakeSureItReturns202(fakeRequestToAPI(expectedCall))

  private def insertToDB: ExpectedCall => Unit = { expectedCall =>
    val headers: Seq[Header] = expectedCall.maybeBadgeId.fold(Seq[Header]())(badgeId => Seq[Header](Header(X_BADGE_ID_HEADER_NAME, badgeId)))

    notificationRepo.save(
      ClientNotification(ClientSubscriptionId(expectedCall.client.csid),
        Notification(ConversationId(expectedCall.conversationId), headers, expectedCall.xml.toString(), MimeTypes.XML), Some(TimeReceivedDateTime), metricsStartDateTime = None))
  }


  feature("Ensure calls to Push Pull are made") {

    scenario(s"correctly with $numberOfClientsToTest clients and $totalNotificationsToBeSent requests") {

      val startTime = DateTime.now()

      val (pushedNotificationExpectations, pullNotificationExpectations) =
        insertTestData(totalNotificationsToBeSent, numberOfClientsToTest)

      val notificationProcessingCompletionTime = DateTime.now() //by now, all requests have been processed

      val expectedPushedNotificationsCounter = pushedNotificationExpectations.values.flatten.size
      val expectedPullNotificationsCounter = pullNotificationExpectations.values.flatten.size
      When(s"totalExpectedPushedNotifications = $expectedPushedNotificationsCounter, totalExpectedPushedNotifications = $expectedPullNotificationsCounter")

      verifyActualNotificationsAreSameAs(pushedNotificationExpectations, pullNotificationExpectations)

      val notificationProcessingTimeInMillis = notificationProcessingCompletionTime.getMillis - startTime.getMillis
      val notificationsProcessedInOneSecond = (totalNotificationsToBeSent.toFloat / notificationProcessingTimeInMillis) * 1000

      Then(s"totalRequestsProcessed = $totalNotificationsToBeSent")
      And(s"Pushed notifications = $expectedPushedNotificationsCounter")
      And(s"PullQ notifications = $expectedPullNotificationsCounter")
      And(s"totalTimeTakenToProcessRequests = $notificationProcessingTimeInMillis millis")
      And(s"notificationsPerSecond=$notificationsProcessedInOneSecond")
      And(s"totalTimeToTest= ${DateTime.now().getMillis - startTime.getMillis} millis")

      //      notificationsProcessedInOneSecond shouldBe > (70f)
    }
  }
}

