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

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor._
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, SnapshotOffer}
import com.typesafe.config.Config
import uk.gov.hmrc.customs.notification.actors.NotificationsActor._
import uk.gov.hmrc.customs.notification.actors.RootActor.NotificationEnqueuedAck
import uk.gov.hmrc.customs.notification.actors.model.NotificationCmd
import uk.gov.hmrc.customs.notification.connectors.PublicNotificationServiceConnector
import uk.gov.hmrc.customs.notification.domain.PublicNotificationRequest
import uk.gov.hmrc.customs.notification.model.ClientId

import scala.concurrent.duration._
import scala.language.postfixOps

/*
Things I have had to change from vanilla akka solution
- persistenceId contains `self.path.name`
- on ReceiveTimeout we send wrapped msg parent to context.parent ! Passivate(stopMessage = PoisonPill)
- root actor gets reference to this entity via `ShardRegion`
- passivation MSG is sent to special `Shard` supervisor
*/
//TODO rename to ClientNotificationQueueActor
object NotificationsActor {
  val ShardName = "Notifications"

  val idExtractor: ShardRegion.ExtractEntityId = {
    case cmd: NotificationCmd => (cmd.clientId, cmd)
  }

  val shardResolver: ShardRegion.ExtractShardId = {
    case cmd: NotificationCmd => (math.abs(cmd.clientId.hashCode) % 100).toString
  }

  // commands
  case class EnqueueCmd(clientId: ClientId, notification: PublicNotificationRequest) extends NotificationCmd
  case class QueryNotificationsCmd(clientId: ClientId) extends NotificationCmd
  case class SendAckCmd(clientId: ClientId, notification: PublicNotificationRequest) extends NotificationCmd

  // events
  case class EnqueuedEvt(notification: PublicNotificationRequest)
  case object NotificationsEvt
  case class SentAckEvt(notification: PublicNotificationRequest)

  case class NotificationsState(events: List[PublicNotificationRequest] = Nil) {
    def updated(evt: EnqueuedEvt): NotificationsState = copy(evt.notification :: events)
    def size: Int = events.length
    def remove(evt: SentAckEvt) = copy(events = this.events.filterNot(_ == evt.notification))
    override def toString: String = events.reverse.toString
  }

  def props(pushConnector: PublicNotificationServiceConnector): Props = Props(classOf[NotificationsActor], pushConnector)
}

class NotificationsActor(pushConnector: PublicNotificationServiceConnector) extends PersistentActor with ActorLogging {

  // self.path.parent.name is the type name (utf-8 URL-encoded)
  // self.path.name is the entry identifier (utf-8 URL-encoded)
  override def persistenceId: String = {
    val id = "NotificationsX" + "-" + self.path.name // resolves to Client ID
    log.info("persistenceId={}", id)
    id
  }

  var state = NotificationsState()

  //Using this scheduled task as the passivation mechanism
  context.setReceiveTimeout(getPersistentEntityTimeout(context.system.settings.config, TimeUnit.SECONDS))

  private def getPersistentEntityTimeout(config: Config, timeUnit: TimeUnit): Duration =
    Duration.create(config.getDuration("persistent-entity-timeout", TimeUnit.SECONDS), timeUnit)

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 minute) {
      case _: IllegalStateException     ⇒ Restart
      case _: Exception                ⇒ Escalate
    }

  def enqueue(event: EnqueuedEvt): Unit =
    state = state.updated(event)

  def remove(event: SentAckEvt): Unit =
    state = state.remove(event)

  val receiveRecover: Receive = {
    case evt: EnqueuedEvt =>
      enqueue(evt)
    case evt: SentAckEvt =>
      remove(evt)
    case SnapshotOffer(_, snapshot: NotificationsState) =>
      state = snapshot
  }

  val receiveCommand: Receive = {
    case EnqueueCmd(_, notification) =>
      persist(EnqueuedEvt(notification)){ event =>
        enqueue(event)
        context.system.eventStream.publish(event)
        log.info(state.toString)
        log.info(s"EnqueueCmd sender().toString() = ${sender().toString()}")
        sendNotification(notification) //TODO: think about doing this in another message handler on a message to self
        sender() ! NotificationEnqueuedAck(notification.clientSubscriptionId)  // Note sender() is originating sender. TODO: return some unique id for ACK
      }
    case QueryNotificationsCmd =>
      persist(NotificationsEvt){ event =>
        context.system.eventStream.publish(event)
        log.info(state.toString)
        log.info(s"QueryNotificationsCmd sender().toString() = ${sender().toString()}")
        sender() ! state
      }
    case SendAckCmd(_, notification) =>
      persist(SentAckEvt(notification)){ event =>
        context.system.eventStream.publish(event)
        log.info(s"SendAckCmd sender().toString() = ${sender().toString()}")
        log.info(s"State PRE remove size = ${state.size}")
        remove(event)
        log.info(s"State POST remove size = ${state.size}")
      }
    //Have been idle too long, time to start passivation process
    case ReceiveTimeout =>
      log.info("Notifications entity with id {} is being passivated due to inactivity", persistenceId)
      /*
      [ERROR] [06/18/2018 11:41:34.576] [ClusterSystem-akka.actor.default-dispatcher-19] [akka.tcp://ClusterSystem@127.0.0.1:2551/system/sharding/Notifications/55/200b01f9-ec3b-4ede-b263-61b626dde232] Persistence failure when replaying events for persistenceId [Notifications-200b01f9-ec3b-4ede-b263-61b626dde232]. Last known sequence number [0]
      java.lang.ClassNotFoundException: uk.gov.hmrc.customs.notification.actors.NotificationsActor$EnqueuedEvt
        at java.net.URLClassLoader.findClass(URLClassLoader.java:381)
        at java.lang.ClassLoader.loadClass(ClassLoader.java:424)
      link to akka-persistence-mongo was helpful which pointed another link
      - https://github.com/scullxbones/akka-persistence-mongo/issues/45
        - https://stackoverflow.com/questions/24593120/classnotfoundexception-in-play-application-with-typesafe-activator
      basically you have to start the app in dev mode as `sbt "start 9821"`
      */
      context.parent ! Passivate(stopMessage = PoisonPill)
    case "print" => println(state)
  }

  def sendNotification(n: PublicNotificationRequest): Unit = {
    val senderActorRef = context.actorOf(SenderActor.props(pushConnector)) // TODO: give a unique name
    senderActorRef ! SenderActor.SendMsg(notification = n, originalSender = self)
  }
}
