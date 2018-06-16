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

//package uk.gov.hmrc.customs.notification.actors
//
//import akka.actor.{Actor, ActorLogging, Props}
//import akka.cluster.sharding.ClusterSharding
//
//object RootActor {
//  case class NotificationEnqueuedAck(id: String) //TODO: think of a unique id for a notification
//  case class SendNotificationMsg(clientId: ClientId, notification: Notification) extends NotificationCmd
//  case class NotificationsMsg(clientId: ClientId) extends NotificationCmd
//
//  def props: Props = Props(classOf[RootActor]) //TODO: do we need a pass in a unique name?
//}
//
//class RootActor extends Actor with ActorLogging {
//
//  val notificationsRegion = ClusterSharding(context.system).shardRegion(NotificationsActor.ShardName)
//
//  override def receive: Receive = {
//    case SendNotificationMsg(clientId, notification) =>
//      forwardCommand(EnqueueCmd(clientId, notification))
//      log.info("forwarded notification to aggregate root for clientId {}", clientId)
//    case NotificationsMsg(clientId) =>
//      log.info("PRE forwarded notifications query")
//      forwardCommand(QueryNotificationsCmd(clientId))
//      log.info("forwarded notifications query to aggregate root for clientId {}", clientId)
//  }
//
//  /**
//    * Looks up the entity child for the supplied id and then
//    * forwards the supplied message to it
//    * @param msg The message to forward
//    */
//  def forwardCommand(msg: NotificationCmd): Unit = {
////TODO: remove
////    val child = lookupOrCreateChild(msg.id)
////    log.info("about to forward msg {} to actor {}", msg, child.toString())
////    // Forwards the message and passes the original sender actor as the sender.
////    // Works, no matter whether originally sent with tell/'!' or ask/'?'.
////    child.forward(msg)
//
//    notificationsRegion.forward(msg)
//  }
//
////TODO: remove - before use of Sharding we were managing lifecycle of persistent entity as child actor of root - now this is not necessary
////  protected def lookupOrCreateChild(clientId: String): ActorRef = {
////    val id = notificationsActorId(clientId)
////    context.child(id).fold {
////      log.info("pre - context = {}", context.children)
////      log.info("Creating new NotificationsActor actor to handle a request for clientId {}", clientId)
////      val child = context.actorOf(entityProps(clientId), id)
////      log.info("post - context = {}", context.children)
////      log.info("Created.")
////      child
////    }{ ref: ActorRef =>
////      log.info("Actor already created, returning ref")
////      ref
////    }
////  }
//
//  def entityProps(clientId: String): Props = NotificationsActor.props
//}
