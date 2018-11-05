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

package integration

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import fs2._
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.reactivestreams.Publisher
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.libs.json.Json
import reactivemongo.api.DB
import uk.gov.hmrc.customs.notification.controllers.CustomMimeType
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
// import fs2._
import fs2.interop.reactivestreams._
// import fs2.interop.reactivestreams._
import cats.effect.{ContextShift, IO}
// import cats.effect.{ContextShift, IO}
import scala.concurrent.ExecutionContext
// import scala.concurrent.ExecutionContext

class ClientNotificationMongoRepoStreamingToFs2Spec extends UnitSpec
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with MockitoSugar
  with MongoSpecSupport
  with Eventually { self =>

  private val validClientSubscriptionId1String: String = "eaca01f9-ec3b-4ede-b263-61b626dde232"
  private val validClientSubscriptionId1UUID = UUID.fromString(validClientSubscriptionId1String)
  private val validClientSubscriptionId1 = ClientSubscriptionId(validClientSubscriptionId1UUID)

  private val validClientSubscriptionId2String: String = "eaca01f9-ec3b-4ede-b263-61b626dde233"
  private val validClientSubscriptionId2UUID = UUID.fromString(validClientSubscriptionId2String)
  private val validClientSubscriptionId2 = ClientSubscriptionId(validClientSubscriptionId2UUID)

  private val validConversationIdString: String = "638b405b-9f04-418a-b648-ce565b111b7b"
  private val validConversationIdStringUUID = UUID.fromString(validConversationIdString)
  private val validConversationId = ConversationId(validConversationIdStringUUID)

  private val payload1 = "<foo1></foo1>"
  private val payload2 = "<foo2></foo2>"
  private val payload3 = "<foo3></foo3>"

  private val headers = Seq(Header("h1","v1"), Header("h2", "v2"))
  private val notification1 = Notification(validConversationId, headers, payload1, CustomMimeType.XmlCharsetUtf8)
  private val notification2 = Notification(validConversationId, headers, payload2, CustomMimeType.XmlCharsetUtf8)
  private val notification3 = Notification(validConversationId, headers, payload3, CustomMimeType.XmlCharsetUtf8)

  private val client1Notification1 = ClientNotification(validClientSubscriptionId1, notification1)
  private val client1Notification2 = ClientNotification(validClientSubscriptionId1, notification2)
  private val client1Notification3 = ClientNotification(validClientSubscriptionId1, notification3)
  private val client2Notification1 = ClientNotification(validClientSubscriptionId2, notification1)

  private val mockNotificationLogger = mock[NotificationLogger]
  private val mockErrorHandler = mock[ClientNotificationRepositoryErrorHandler]

  private lazy implicit val emptyHC: HeaderCarrier = HeaderCarrier()
  private val timeoutInSeconds = 2
  private val duration = org.joda.time.Duration.standardSeconds(timeoutInSeconds)
  private val five = 5
  private val pushConfigWithMaxFiveRecords = PushNotificationConfig(
    pollingDelay = 1 second,
    lockDuration = org.joda.time.Duration.ZERO,
    maxRecordsToFetch = five
  )

  private val mongoDbProvider = new MongoDbProvider {
    override val mongo: () => DB = self.mongo
  }

  val lockRepository = new LockRepository
  val lockRepo: LockRepo = new LockRepo(mongoDbProvider, mockNotificationLogger) {
    val db: () => DB = () => mock[DB]
    override val repo: LockRepository = lockRepository
  }

  private def configWithMaxRecords(maxRecords: Int = five): CustomsNotificationConfig = {
    val config = new CustomsNotificationConfig{
      override def pullExcludeConfig: PullExcludeConfig = ???
      override def maybeBasicAuthToken: Option[String] = None
      override def notificationQueueConfig: NotificationQueueConfig = mock[NotificationQueueConfig]
      override def googleAnalyticsSenderConfig: GoogleAnalyticsSenderConfig = mock[GoogleAnalyticsSenderConfig]
      override def pushNotificationConfig: PushNotificationConfig = pushConfigWithMaxFiveRecords.copy(maxRecordsToFetch = maxRecords)
    }
    config
  }

  private val repository = new ClientNotificationMongoRepo(configWithMaxRecords(five), mongoDbProvider, lockRepo, mockErrorHandler, mockNotificationLogger)
  private val repositoryWithOneMaxRecord = new ClientNotificationMongoRepo(configWithMaxRecords(1), mongoDbProvider, lockRepo, mockErrorHandler, mockNotificationLogger)

  override def beforeEach() {
    await(repository.drop)
    await(lockRepository.drop)
    Mockito.reset(mockErrorHandler, mockNotificationLogger)
  }

  override def afterAll() {
    await(repository.drop)
    await(lockRepository.drop)
  }

  private def collectionSize: Int = {
    await(repository.collection.count())
  }

  private def selector(clientSubscriptionId: ClientSubscriptionId) = {
    Json.obj("csid" -> clientSubscriptionId.id)
  }

  private def logVerifier(logLevel: String, logText: String) = {
    PassByNameVerifier(mockNotificationLogger, logLevel)
      .withByNameParam(logText)
      .withParamMatcher(any[HeaderCarrier])
      .verify()
  }

  implicit val system = ActorSystem()
  implicit val mater = ActorMaterializer()

  "streaming mongo source" should {
    "streaming fetch by clientSubscriptionId should return a two records" in {
      implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)
      implicit val timer = IO.timer(ExecutionContext.Implicits.global)

      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))

      def stage1(cn: ClientNotification) = IO{println(s"XXXXX Stage1 cn=$cn"); cn}
      def stage2(cn: ClientNotification) = IO{println(s"XXXXX Stage2 cn=$cn"); cn}

      val publisher: Publisher[ClientNotification] = repository.streamingPublisherFetch(validClientSubscriptionId1)
      val fs2Stream: Stream[IO, ClientNotification] = publisher.toStream[IO]
      val actual: Seq[ClientNotification] =
        fs2Stream
          .evalMap(stage1)
          .evalMap(stage2)
          .compile.toList.unsafeRunSync()

      println(s"YYYYYYYY $actual")

    }
  }

}
