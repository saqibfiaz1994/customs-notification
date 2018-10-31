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

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, RestartFlow, RunnableGraph, Sink, Source}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

class StreamsTalkSpec extends UnitSpec {
  implicit val system = ActorSystem()
  implicit val mater = ActorMaterializer()
  override implicit val defaultTimeout = 20 seconds

  case class Notification(i: Int)

  def mylog(x: Any) = println(x)

  "simplest example" in {
    val source = Source(1 to 5).map(i => Notification(i))
    val sink:Sink[Notification, Future[Done]] = Sink.foreach[Notification](mylog)
    val dataflow:RunnableGraph[NotUsed] = source.to(sink)
    dataflow.run
  }

  "simplest flow" in {
    val source = Source(1 to 5)
    val sink2:Sink[Int, Future[Int]] = Sink.fold(0)(_ + _)
    val flow = Flow[Int].map(_*2).filter(_ % 2 == 0)
    val future = source.via(flow).toMat(sink2)(Keep.right).run
    future.onComplete(mylog)(system.dispatcher)
    await(future)
  }

  "simplest async flow" in {
    //https://stackoverflow.com/questions/35146418/difference-between-map-and-mapasync
    val start = System.currentTimeMillis()
    val maxWaitMillis = 1000

    val mapper: Int => Future[String] = i => Future{
      val sleep = Random.nextInt(maxWaitMillis)
      Thread.sleep(sleep)
      s"item $i, sleep = $sleep  elapsed=${System.currentTimeMillis() - start}"
    }

    val parallelism = 20
    val asyncFlow = Flow[Int].mapAsync[String](parallelism)(mapper)

    val maxItems = 20
    val source:Source[Int, NotUsed] = Source(1 to maxItems)

    val sink2 = Sink.foreach[String](mylog)

    val fut2 = source.via(asyncFlow).toMat(sink2)(Keep.right).run
    fut2.onComplete(mylog)(system.dispatcher)

    await(fut2)
  }

  "graph builder" in {
    val g = RunnableGraph.fromGraph(GraphDSL.create() {
      implicit builder: GraphDSL.Builder[NotUsed] =>

        import GraphDSL.Implicits._
        val in = Source(1 to 5)
        val out = Sink.foreach[Int](i => print(s"$i "))
        val f1 = Flow[Int].map(_*2)
        val f2 = Flow[Int].map(_ * 1)
        val f3 = Flow[Int].map(_*2)
        val f4 = Flow[Int].map(_+1)

        val bcast = builder.add(Broadcast[Int](2))
        val merge = builder.add(Merge[Int](2))

        in ~> f1 ~> bcast ~> f2 ~> merge  ~> f4 ~> out
                    bcast ~> f3 ~> merge
        ClosedShape
    })
    g.run
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


}
