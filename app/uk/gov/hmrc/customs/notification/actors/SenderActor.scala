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

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import uk.gov.hmrc.customs.notification.actors.NotificationsActor.SendAckCmd
import uk.gov.hmrc.customs.notification.actors.SenderActor.SendMsg
import uk.gov.hmrc.customs.notification.connectors.PublicNotificationServiceConnector
import uk.gov.hmrc.customs.notification.domain.PublicNotificationRequest

import scala.concurrent.ExecutionContext.Implicits.global

object SenderActor {
  // Messages
  //TODO: keep reference to original sender
  case class SendMsg(notification: PublicNotificationRequest, sendCount: Int = 0, originalSender: ActorRef)

  def props(pushConnector: PublicNotificationServiceConnector): Props = Props(classOf[SenderActor], pushConnector) //TODO: do we need a pass in a unique name?
}

class SenderActor(pushConnector: PublicNotificationServiceConnector) extends Actor with ActorLogging {
  var sendCount = 0

  override def postStop(): Unit = {
    log.info("about to stop")
    super.postStop()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.info("in preRestart")
    message.fold{
      val msg = "WTF! This should never happen!"
      log.error(msg)
      throw new RuntimeException(msg)
    }{message =>
      val sendMsg = message.asInstanceOf[SendMsg]
      val sendMsg2 = sendMsg.copy(sendCount = this.sendCount)
      log.info("sending failed message {} to myself", sendMsg2)
      self ! sendMsg2
    }

    super.preRestart(reason, message)
  }

  override def receive: Receive = {
    case SendMsg(notification, count, originalSender) => {
      log.info(s"PRE sendCount=$sendCount")
      this.sendCount = count + 1
      log.info(s"POST sendCount=$sendCount")
      pushConnector.send(notification).map{ _ =>
        originalSender ! SendAckCmd(notification.clientSubscriptionId, notification)
        self ! PoisonPill
      }
    }
  }

}
