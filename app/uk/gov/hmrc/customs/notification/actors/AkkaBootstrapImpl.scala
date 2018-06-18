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

package uk.gov.hmrc.customs.notification.actors

import javax.inject.Inject

import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import uk.gov.hmrc.customs.notification.connectors.PublicNotificationServiceConnector

import scala.concurrent.duration._

// To override `shard-node-port` port use
// sbt '; set javaOptions += "-Dpidfile.path=/dev/null" ; set javaOptions += "-Dshard-node-port=2552" ; start 9821'
class AkkaBootstrapImpl @Inject() (
  configuration: Configuration,
  pushConnector: PublicNotificationServiceConnector) extends AkkaBootstrap {

  val root = {
    println("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX in AkkaBootstrapImpl constructor !!!!!!")

    // bootstrap akka cluster sharding
    val askTimeout = 10 seconds
    val port: Int = configuration.getInt("shard-node-port").get
    println(s"XXXXXXXXXXXXXXXXXXXXXXXXXX shard-node-port=$port")
    val theConfig = ConfigFactory.load()
    val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
      withFallback(theConfig)
    val clusterSystem = ActorSystem("ClusterSystem", config) //TODO: pass in config to constructor
    ClusterSharding(clusterSystem).start(
      typeName = NotificationsActor.ShardName,
      entityProps = NotificationsActor.props(pushConnector),
      settings = ClusterShardingSettings(clusterSystem),
      extractEntityId = NotificationsActor.idExtractor,
      extractShardId = NotificationsActor.shardResolver)
    val rootActor = clusterSystem.actorOf(RootActor.props(pushConnector), "rootActor")
    println(s"XXXXXXXXXXXXXXXXXXXXXXXXXX rootActor=$rootActor")
    rootActor
  }

}
