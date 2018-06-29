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

package unit.services

import java.util.UUID

import org.joda.time.DateTime
import uk.gov.hmrc.customs.notification.domain._
import uk.gov.hmrc.customs.notification.repo.LockOwnerId

object ClientWorkerTestData {
  lazy val CsidOne = ClientSubscriptionId(UUID.fromString("eaca01f9-ec3b-4ede-b263-61b626dde231"))
  val CsidTwo = ClientSubscriptionId(UUID.fromString("eaca01f9-ec3b-4ede-b263-61b626dde232"))
  lazy val CsidOneLockOwnerId = new LockOwnerId(CsidOne.id.toString)
  val HeaderTuples = Seq("h1" -> "v1")
  val Headers = HeaderTuples.map(t => Header(t._1, t._2))
  val PayloadOne = "PAYLOAD_ONE"
  val PayloadTwo = "PAYLOAD_TWO"
  val ContentType = "CONTENT_TYPE"
  val NotificationOne = Notification(HeaderTuples, PayloadOne, ContentType)
  val NotificationTwo = Notification(HeaderTuples, PayloadTwo, ContentType)
  val TimeStampOne = DateTime.now
  val TimeStampTwo = TimeStampOne.plus(1000) //TODO
  val ClientNotificationOne = ClientNotification(CsidOne, NotificationOne, TimeStampOne)
  val ClientNotificationTwo = ClientNotification(CsidOne, NotificationTwo, TimeStampTwo)
  val DeclarantCallbackDataOne = DeclarantCallbackData("URL", "SECURITY_TOKEN")
  val ConversationId = "TODO_ADD_CONVERSATION_ID_TO_MODEL"
  val pnrOne  =PublicNotificationRequest(CsidOne.id.toString, PublicNotificationRequestBody("URL", "SECURITY_TOKEN", ConversationId, Headers, PayloadOne))
  val pnrTwo  =PublicNotificationRequest(CsidOne.id.toString, PublicNotificationRequestBody("URL", "SECURITY_TOKEN", ConversationId, Headers, PayloadTwo))
}



