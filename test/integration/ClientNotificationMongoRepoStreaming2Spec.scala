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
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape, Graph}
import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, Partition, RestartFlow, RunnableGraph, Sink, Source}
import com.fasterxml.jackson.databind.ser.std.StdJdkSerializers.AtomicBooleanSerializer
import org.joda.time.{DateTime, DateTimeZone, Seconds}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.libs.json.Json
import reactivemongo.akkastream.State
import reactivemongo.api.{Cursor, DB}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.JsObjectDocumentWriter
import reactivemongo.play.json.collection.JSONCollection
import uk.gov.hmrc.customs.notification.controllers.CustomMimeType
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.repo._
import uk.gov.hmrc.customs.notification.services.Streamer
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.MongoSpecSupport
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal

class ClientNotificationMongoRepoStreaming2Spec extends UnitSpec
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
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))

      val src: Source[ClientNotification, Future[State]] = repository.streamingFetch(validClientSubscriptionId1)
      val sink: Sink[ClientNotification, Future[Done]] = Sink.foreach[ClientNotification](n => println(s"XXXXXXXXXXXXXXXXX: $n"))
      val dataflow: RunnableGraph[Future[Done]] = src.toMat(sink)(Keep.right)
      //      val dataflow: RunnableGraph[Future[State]] = src.to(sink)
      await(dataflow.run)
    }
  }



























  "random mutterings to my self" should {
    "bar" in {
      await(repository.save(client1Notification1))
      await(repository.save(client1Notification2))
      await(repository.save(client2Notification1))
      val x = new Streamer()

      val src: Source[ClientNotification, Future[State]] = x.stream(repository.collection)
      val sink: Sink[ClientNotification, Future[Done]] = Sink.foreach[ClientNotification](n => println(s"XXXXXXXXXXXXXXXXX: $n"))
      val dataflow: RunnableGraph[Future[Done]] = src.toMat(sink)(Keep.right)

      await(dataflow.run)
    }

    "restartable flow - a flow stage with N retries after a backoff" in {
      val source:Source[Int, NotUsed] = Source(1 to 5)
      @volatile var counter = 0
      val flow = Flow[Int].map{i =>
        counter += 1
        println(s"XXXXXX in flow i=$i counter=$counter")
        if (i == 2 && counter < 3) {
          throw new RuntimeException("BOOM!")
        }
        i
      }.log("flow boom")
      val restartFlow = RestartFlow.onFailuresWithBackoff(
        minBackoff = 1.second,
        maxBackoff =  20.second,
        randomFactor =  0.2,
        maxRestarts =  6)(() => flow)
      val sink2:Sink[Int, Future[Int]] = Sink.fold(0)(_+_)
      val fut2 = source.via(restartFlow).toMat(sink2)(Keep.right).run
      fut2.onComplete(println)(system.dispatcher)
      await(fut2)
    }

    "restartable flow that ultimately fails after restarts - a flow stage with N retries after a backoff" in {
      val source:Source[Int, NotUsed] = Source(1 to 5)
      var restartCount = 0
      def callEndpoint(i: Int): Future[Int] = {
        if (i == 3) {
          throw new RuntimeException("BOOM!")
        }
        Future(i)
      }.recoverWith{
        case e: RuntimeException/* if restartCount == 3*/ =>
          println(s"############## Recovering")
          Future(i)
      }
      val flow = Flow[Int].mapAsync(1){i =>
        println(s"XXXXXX in flow i=$i restartCount=$restartCount")
        callEndpoint(i)
      }
      def flowFactory = () => {
        restartCount += 1
        println(s"flowFactory restartCount=$restartCount")
        flow
      }
      val restartFlow: Flow[Int, Int, NotUsed] = RestartFlow.onFailuresWithBackoff(
        minBackoff = 1.second,
        maxBackoff =  20.second,
        randomFactor =  0.2,
        maxRestarts =  3)(flowFactory)
      val sink2: Sink[Int, Future[Done]] = Sink.foreach[Int](i => println(s"XXXXXX SINK i=$i"))
      val fut2 = source.via(restartFlow).toMat(sink2)(Keep.right).run
      fut2.onComplete(println)(system.dispatcher)
      await(fut2)
    }

    /*
    TODO:
    - timers
    DONE
    - multiple sinks in a RunnableGraph
    - getting a future result of materialized sink(s) see https://stackoverflow.com/questions/42426741/akka-streams-how-do-i-get-materialized-sink-output-from-graphdsl-api
    */

    // https://stackoverflow.com/questions/42426741/akka-streams-how-do-i-get-materialized-sink-output-from-graphdsl-api
    "graph builder that materialises a sink" in {
      val source = Source(1 to 3)
      case class Report(list: Seq[Int] = Seq.empty)
      val sink = Sink.fold[Report, Int](Report()){
        case (Report(list), i) => Report(list :+ i)
      }
      val flow = Flow[Int].map(i => i + 1)

      val graphModel: Graph[ClosedShape.type, Future[Report]] = GraphDSL.create(sink) { implicit b =>s =>
        import GraphDSL.Implicits._

        source ~> flow ~> s

        // ClosedShape is just fine - it is always the shape of a RunnableGraph
        ClosedShape
      }
      val g: RunnableGraph[Future[Report]] = RunnableGraph.fromGraph(graphModel)
      println(await(g.run()))
    }

    "graph builder that broadcasts to 2 sinks, and materialises only one sink" in {
      val source = Source(1 to 3)
      val sinkIgnore = Sink.ignore
      val sinkHead: Sink[Int, Future[Option[Int]]] = Sink.headOption[Int]
      val flow = Flow[Int].map(i => {println(s"i=$i"); i + 1})

      val graphModel: Graph[ClosedShape.type, Future[Option[Int]]] = GraphDSL.create(sinkHead) { implicit b => s =>
        import GraphDSL.Implicits._
        val bcast = b.add(Broadcast[Int](2))

        source ~> bcast ~> flow ~> sinkIgnore
                  bcast ~> s
        // ClosedShape is just fine - it is always the shape of a RunnableGraph
        ClosedShape
      }
      val g: RunnableGraph[Future[Option[Int]]] = RunnableGraph.fromGraph(graphModel)
      val maybeFirst: Option[Int] = await(g.run())
      println("materialised = " + maybeFirst)
    }

    "Complex linear async graph flow" in {
      case class Notification(i: Int)
      case class Declarant(i: Int)
      case class Holder(n: Notification, isPush: Boolean = true, d: Option[Declarant] = None, error: Option[String] = None)

      def asyncFlow(isPush: Boolean)(block: Holder => Future[Holder]): Holder => Future[Holder] = h =>
        if (h.isPush == isPush) {
          block(h)
        } else {
          Future.successful(h)
        }

      val g: RunnableGraph[NotUsed] = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val seq = scala.collection.immutable.Seq(1,2,3,4,5)
          val in = Source(seq).map { i =>
            //            println(s"i=$i")
            Holder(Notification(i), i % 2 == 0)
          }
          val lookupDec = Flow[Holder].mapAsync(1)(asyncFlow(isPush = true){h =>
            Future(h.copy(d = Some(Declarant(h.n.i))))
          })
          val sink = Sink.foreach[Holder](h => println(s"push $h "))
          val push = Flow[Holder].mapAsync(1) {
            asyncFlow(isPush = true) { h =>
              println(s"doing push stuff for $h")
              Future(h)
            }
          }
          val pull = Flow[Holder].mapAsync(1) {
            asyncFlow(isPush = false) { h =>
              println(s"doing pull stuff for $h")
              Future(h.copy(n = h.n.copy(i = h.n.i * 10), isPush = false))
            }
          }

          in ~> lookupDec ~> push ~> pull ~> sink

          ClosedShape
      })
      g.run
      Thread.sleep(1000)
    }

    "XOR graph flow" in {
      case class Notification(i: Int, isPush: Boolean = true)
      case class Declarant(i: Int)
      case class PushNotification(n: Notification, d: Option[Declarant])
      def portMapper(value: Notification): Int = value match {
        case Notification(i, _) if i % 2 == 0 => 0
        case _ => 1
      }

      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val seq = scala.collection.immutable.Seq(1,2,3,4,5)
          val in = Source(seq).map(i => Notification(i))
          val lookupDec = Flow[Notification].map(n => PushNotification(Notification(n.i), Some(Declarant(n.i))))
          val push = Sink.foreach[PushNotification](n => println(s"push $n "))
          val pull = Sink.foreach[Notification](n => println(s"pull $n "))
          val f1 = Flow[Notification].map(n =>Notification(n.i + 1))

          val router = builder.add(Partition[Notification](2, portMapper))

          in ~> f1 ~> router ~> lookupDec ~> push
          router ~> pull
          ClosedShape
      })
      g.run
    }

    "Complex linear async graph flow 2" in {
      val routeAllToPull = new AtomicBoolean(false)

      case class Notification(i: Int)
      case class Declarant(i: Int)
      case class Holder(n: Notification, isPush: Boolean = true, d: Option[Declarant] = None, error: Option[String] = None) {
        def toPull = this.copy(isPush = false, d = None)
        def toPush = this.copy(isPush = true, d = None)
        def toPush(dec: Declarant) = this.copy(isPush = true, d = Some(dec))
      }

      val sleepMillis = 100
      class Connector() {
        def clientDetails(i: Int, fail: Boolean = false): Future[Option[Declarant]] = {
          scala.concurrent.blocking(Thread.sleep(sleepMillis))
          if (fail) {
            println(s"clientDetails BOOM! i = $i")
            throw new RuntimeException("DECLARANT LOOKUP FAILED")
          }
          Future( if (i == -1) None else Some(Declarant(i)))
        }
        def push(n: Notification, d: Declarant, fail: Boolean = false): Future[Unit] = {
          scala.concurrent.blocking(Thread.sleep(sleepMillis))
          if (fail) throw new RuntimeException("PUSH FAILED") else Future.successful(())
        }
        def pull(n: Notification, fail: Boolean = false): Future[Unit] = {
          scala.concurrent.blocking(Thread.sleep(sleepMillis))
          if (fail) throw new RuntimeException("PULL FAILED") else Future.successful(())
        }
      }
      val connector = new Connector

      def asyncFlow(isPush: Boolean)(block: Holder => Future[Holder]): Holder => Future[Holder] = h =>
        if (h.isPush == isPush) {
          block(h)
        } else {
          Future.successful(h)
        }

      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val seq = scala.collection.immutable.Seq(1,2,3,4,5,6,7,8)
          val in = Source(seq).map { i =>
            Holder(Notification(i), true)
          }
          val pushOrPullRouter = Flow[Holder].map{h =>
            println(s"pushOrPullRouter h=$h")
            if (routeAllToPull.get) h.toPull else h.toPush
          }
          val lookupDec = Flow[Holder].mapAsync(1)(asyncFlow(isPush = true){ h =>
            connector.clientDetails(h.n.i).map{
              case None =>
                routeAllToPull.set(true)
                h.toPull
              case Some(d) =>
                h.toPush(d)
            }
          })
          val push = Flow[Holder].mapAsync(1) {
            println(s"PUSH in push")
            asyncFlow(isPush = true) { h =>
              println(s"doing push stuff for $h")
              val f = connector.push(h.n, h.d.get).map(_ => {
                println(s"PUSH OK")
                h
              })
              f.onFailure{
                case NonFatal(e) => {
                  println(s"PUSH FAILED")
                  routeAllToPull.set(true)
                  h.toPull
                }
              }
              f
            }
          }
          val pull = Flow[Holder].mapAsync(1) {
            asyncFlow(isPush = false) { h =>
              println(s"doing pull stuff for $h")
              Future(h.copy(n = h.n.copy(i = h.n.i * 10), isPush = false))
            }
          }
          val sink = Sink.foreach[Holder](h => println(s"XXXXX HOLDER = $h "))

          in ~> pushOrPullRouter ~> lookupDec ~> push ~> pull ~> sink

          ClosedShape
      })
      g.run
      Thread.sleep(3000)
    }

//    "Complex linear async graph flow 2" in {
//      implicit val system = ActorSystem()
//      implicit val mater = ActorMaterializer()
//      val sleepMillis = 100
//      val routeAllToPull = new AtomicBoolean(false)
//
//      class Connector() {
//        def clientDetails(i: Int, fail: Boolean = false): Future[Option[Declarant]] = {
//          scala.concurrent.blocking(Thread.sleep(sleepMillis))
//          if (fail) throw new RuntimeException("DECLARANT LOOKUP FAILED")
//          Future( if (i % 2 == 0) Some(Declarant(i)) else None)
//        }
//        def push(n: PushNotification, d: Declarant, fail: Boolean = false): Future[Unit] = {
//          scala.concurrent.blocking(Thread.sleep(sleepMillis))
//          if (fail) throw new RuntimeException("PUSH FAILED") else Future.successful(())
//        }
//        def pull(n: Notification, fail: Boolean = false): Future[Unit] = {
//          scala.concurrent.blocking(Thread.sleep(sleepMillis))
//          if (fail) throw new RuntimeException("PULL FAILED") else Future.successful(())
//        }
//      }
//
//
//      trait Notification{
//        val i: Int
//      }
//      case class PushNotification(i: Int, d: Option[Declarant] = None) extends Notification
//      case class PullNotification(i: Int) extends Notification
//      case class Declarant(i: Int)
//      case class Holder(n: PushNotification, isPush: Boolean = true, d: Option[Declarant] = None, error: Option[String] = None)
//
//      def asyncFlow(isPush: Boolean)(block: Holder => Future[Holder]): Holder => Future[Holder] = h =>
//        if (h.isPush == isPush) {
//          block(h)
//        } else {
//          Future.successful(h)
//        }
//
//      val g: RunnableGraph[NotUsed] = RunnableGraph.fromGraph(GraphDSL.create() {
//        implicit builder: GraphDSL.Builder[NotUsed] =>
//
//          import GraphDSL.Implicits._
//          val seq = scala.collection.immutable.Seq(1,2,3,4,5)
//          val in = Source(seq).map { i =>
//            //            println(s"i=$i")
//            PushNotification(i)
//          }
//          val routeAllToPull = new AtomicBoolean(false)
////          val router = Flow[Notification].map{n =>
////            if (routeAllToPull.get || n.i % 2 != 0) PushNotification(n.i) else PullNotification(n.i)
////          }
//          val lookupDecIfPush = Flow[Notification].mapAsync(1){
//            case pull: PullNotification => Future(pull)
//            case push: PushNotification =>
//              Future {
//                if (push.i % 2 != 2) push.copy(d = Some(Declarant(push.i))) else PullNotification(push.i)
//              }
//          }
//          val sink = Sink.foreach[Notification](h => println(s"notification=$h"))
//          val pushIfpush = Flow[Notification].mapAsync(1) {
//            case pull: PullNotification => Future( pull )
//            case push: PushNotification =>
//              Future {
//                if (push.i == 8) {
//                  routeAllToPull.set(true)
//                  PullNotification(push.i)
//                } else {
//                  push
//                }
//              }
//          }
//          val pullIfpull = Flow[Notification].mapAsync(1) {
//            case pull: PullNotification => Future( pull )
//            case push: PushNotification => Future( push )
//          }
//          val router = builder.add(Partition[Notification](2, _ => if (routeAllToPull.get) 1 else 0))
//          val merge = builder.add(Merge[Notification](2))
//          //TODO: think about exceptions
//
//          in ~> pushOrPullRouter ~> lookupDecIfPush ~> pushIfPush ~> pullIfPull ~> sink
//
////          source ~> router ~> lookupDec ~> push ~> merge ~> sink
////                    router ~> pull ~> merge
//
//          ClosedShape
//      })
//      g.run
//      Thread.sleep(1000)
//    }
//
  }

}
